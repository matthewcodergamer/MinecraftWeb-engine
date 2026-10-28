//! mc-body — the "Zombie Hybrid" Body renderer.
//!
//! The Java Brain owns world generation, the 20 TPS tick, physics, collision
//! and mod logic. This crate only renders what the Brain reports: block-ID
//! sections, camera pose, entity transforms. Nothing here may simulate motion,
//! gravity, friction or redstone — those quirks must stay in Java.
//!
//! Milestone 2a (this file): per-section GPU arena + CPU frustum culling.
//! The synthetic island still drives the pipeline; meshing remains synchronous
//! until M2b moves it to a Worker and packs the vertex format.
//! Still no JavaScript rendering logic — the page only loads wasm and calls run.

#[cfg(not(target_arch = "wasm32"))]
compile_error!(
    "mc-body is a wasm32-only WebGPU renderer; build with --target wasm32-unknown-unknown"
);

mod arena;
mod bridge;
mod controls;
mod cull;
mod material;
mod math;
mod mesh;
mod textures;

use std::cell::RefCell;
use std::rc::{Rc, Weak};

use wasm_bindgen::prelude::*;
use wasm_bindgen::JsCast;
use wasm_bindgen_futures::spawn_local;

use arena::ChunkArena;
use cull::Frustum;
use mesh::Vertex;
use textures::BlockTextures;

/// iPhone 11 reports dpr = 2; Pro iPhones report 3. Capping at 2 bounds
/// fragment work and framebuffer memory on the devices we target.
const MAX_DPR: f64 = 2.0;
const CANVAS_ID: &str = "mc-canvas";
const DEPTH_FORMAT: wgpu::TextureFormat = wgpu::TextureFormat::Depth32Float;

/// Synthetic island is 8x8 chunks (128x128 blocks).
/// Vanilla overworld daytime sky (renderpearl's clear colour) and matching fog.
const SKY_COLOR: [f32; 3] = [0.47, 0.63, 0.84];
const FOG_START: f32 = 96.0;
const FOG_END: f32 = 192.0;
const UNIFORM_SIZE: u64 = 112;

struct Renderer {
    instance: wgpu::Instance,
    canvas: web_sys::HtmlCanvasElement,
    surface: wgpu::Surface<'static>,
    device: wgpu::Device,
    queue: wgpu::Queue,
    config: wgpu::SurfaceConfiguration,

    pipeline: wgpu::RenderPipeline,
    uniform_buffer: wgpu::Buffer,
    bind_group: wgpu::BindGroup,
    textures: BlockTextures,

    arena: ChunkArena,
    visible: Vec<usize>,
    frame_index: u64,
    start_ms: f64,
    last_frame_ms: f64,
    fps: f32,
    draw_ms: f32,

    #[allow(dead_code)] // the view keeps the texture alive; handle is owned here
    depth_texture: wgpu::Texture,
    depth_view: wgpu::TextureView,

    stats_text: String,
}

impl Renderer {
    fn configure(&self) {
        self.surface.configure(&self.device, &self.config);
    }

    /// The canvas may lose its swapchain (tab backgrounded, GPU reset, rotation).
    fn recover_surface(&mut self) {
        if let Ok(surface) = self
            .instance
            .create_surface(wgpu::SurfaceTarget::Canvas(self.canvas.clone()))
        {
            self.surface = surface;
            self.configure();
        }
    }

    /// Keep the drawing buffer (and depth attachment) in sync with the CSS
    /// size, capped at 2x DPR.
    fn sync_size(&mut self) {
        let (width, height) = canvas_pixel_size(&self.canvas);
        if width == self.config.width && height == self.config.height {
            return;
        }
        self.canvas.set_width(width);
        self.canvas.set_height(height);
        self.config.width = width;
        self.config.height = height;
        self.configure();

        let (depth_texture, depth_view) = create_depth(&self.device, width, height);
        self.depth_texture = depth_texture;
        self.depth_view = depth_view;
    }

    fn update_camera(&mut self) -> ([f32; 16], [f32; 3]) {
        let aspect = self.config.width.max(1) as f32 / self.config.height.max(1) as f32;
        let now = js_sys::Date::now();
        let dt = ((now - self.last_frame_ms) * 0.001) as f32;
        self.last_frame_ms = now;
        self.fps = if dt > 0.0 { 1.0 / dt } else { 0.0 };
        controls::tick(dt);

        let pose = controls::pose(aspect);
        let mut uniform = [0u8; UNIFORM_SIZE as usize];
        uniform[0..64].copy_from_slice(&math::to_bytes(&pose.view_projection));
        let eye_and_fog = [
            pose.position[0],
            pose.position[1],
            pose.position[2],
            0.0,
            SKY_COLOR[0],
            SKY_COLOR[1],
            SKY_COLOR[2],
            1.0,
            FOG_START,
            FOG_END,
            (now - self.start_ms) as f32 * 0.001,
            0.0,
        ];
        for (i, value) in eye_and_fog.iter().enumerate() {
            uniform[64 + i * 4..68 + i * 4].copy_from_slice(&value.to_le_bytes());
        }
        self.queue.write_buffer(&self.uniform_buffer, 0, &uniform);
        (pose.view_projection, pose.position)
    }

    fn draw(&mut self) {
        self.sync_size();
        let frame_start = js_sys::Date::now();
        // Incremental bridge: re-mesh at most a few dirty sections per frame,
        // bounded by a 3 ms budget so a block edit never stalls the frame.
        let mut remeshed = 0;
        while remeshed < 4 && js_sys::Date::now() - frame_start < 3.0 {
            match bridge::next_dirty() {
                Some(key) => {
                    if let Some(mesh) = bridge::remesh(key) {
                        self.arena.apply(&self.device, &self.queue, key, &mesh);
                        remeshed += 1;
                    }
                }
                None => break,
            }
        }
        let (view_projection, _eye) = self.update_camera();

        let frustum = Frustum::from_view_projection(&view_projection);
        self.visible.clear();
        let mut visible_triangles = 0u32;
        for (index, slot) in self.arena.slots.iter().enumerate() {
            if slot.index_count > 0 && frustum.intersects_aabb(slot.min, slot.max) {
                self.visible.push(index);
                visible_triangles += slot.index_count / 3;
            }
        }

        self.frame_index += 1;
        if self.frame_index % 15 == 1 {
            set_stats(&format!(
                "{} · {:.0} fps · {:.1} ms\n{}/{} sections · {}/{} tris drawn",
                self.stats_text,
                self.fps,
                self.draw_ms,
                self.visible.len(),
                self.arena.slots.iter().filter(|s| s.index_count > 0).count(),
                visible_triangles,
                self.arena.total_indices / 3
            ));
        }

        let frame = match self.surface.get_current_texture() {
            wgpu::CurrentSurfaceTexture::Success(frame) => frame,
            wgpu::CurrentSurfaceTexture::Suboptimal(frame) => {
                drop(frame);
                self.configure();
                return;
            }
            // Compositor is not ready (background tab / occluded window).
            wgpu::CurrentSurfaceTexture::Timeout | wgpu::CurrentSurfaceTexture::Occluded => {
                return;
            }
            wgpu::CurrentSurfaceTexture::Outdated => {
                self.configure();
                return;
            }
            wgpu::CurrentSurfaceTexture::Lost => {
                self.recover_surface();
                return;
            }
            wgpu::CurrentSurfaceTexture::Validation => {
                // No error scope captured this; wgpu already reported it.
                return;
            }
        };

        let view = frame
            .texture
            .create_view(&wgpu::TextureViewDescriptor::default());
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("mc-body/frame"),
            });
        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("mc-body/chunks"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color {
                            r: SKY_COLOR[0] as f64,
                            g: SKY_COLOR[1] as f64,
                            b: SKY_COLOR[2] as f64,
                            a: 1.0,
                        }),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: Some(wgpu::RenderPassDepthStencilAttachment {
                    view: &self.depth_view,
                    depth_ops: Some(wgpu::Operations {
                        load: wgpu::LoadOp::Clear(1.0),
                        store: wgpu::StoreOp::Store,
                    }),
                    stencil_ops: None,
                }),
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.bind_group, &[]);
            for &index in &self.visible {
                let slot = &self.arena.slots[index];
                pass.set_vertex_buffer(0, slot.vertex_buffer.slice(..));
                pass.set_index_buffer(slot.index_buffer.slice(..), wgpu::IndexFormat::Uint32);
                pass.draw_indexed(0..slot.index_count, 0, 0..1);
            }
        }
        self.queue.submit(Some(encoder.finish()));
        self.queue.present(frame);
        self.draw_ms = (js_sys::Date::now() - frame_start) as f32;
    }
}

thread_local! {
    static RENDERER: RefCell<Option<Renderer>> = const { RefCell::new(None) };
    // Holds the RAF closure alive. The closure holds only a Weak reference
    // back to the slot, so the pair does not form a permanent cycle.
    static FRAME_LOOP: RefCell<Option<Rc<RefCell<Option<Closure<dyn FnMut()>>>>>> =
        const { RefCell::new(None) };
}

/// Entry point called by the harness page after `init()` resolved.
#[wasm_bindgen]
pub fn run() {
    console_error_panic_hook::set_once();
    spawn_local(async {
        match init().await {
            Ok(renderer) => {
                set_status("");
                set_stats(&renderer.stats_text);
                log("WebGPU chunk renderer ready");
                RENDERER.with(|slot| *slot.borrow_mut() = Some(renderer));
                start_frame_loop();
            }
            Err(err) => {
                let message = err.as_string().unwrap_or_else(|| format!("{err:?}"));
                report_error(&format!("mc-body init failed: {message}"));
            }
        }
    });
}

async fn init() -> Result<Renderer, JsValue> {
    let window = web_sys::window().ok_or_else(|| js_err("no window"))?;
    let document = window.document().ok_or_else(|| js_err("no document"))?;
    let canvas = document
        .get_element_by_id(CANVAS_ID)
        .ok_or_else(|| js_err("missing #mc-canvas"))?
        .dyn_into::<web_sys::HtmlCanvasElement>()
        .map_err(|_| js_err("#mc-canvas is not an HtmlCanvasElement"))?;

    let (width, height) = canvas_pixel_size(&canvas);
    canvas.set_width(width);
    canvas.set_height(height);
    controls::install(canvas.clone());

    // WebGPU only; on wasm32 this resolves to navigator.gpu.
    let instance = wgpu::Instance::new({
        let mut desc = wgpu::InstanceDescriptor::new_without_display_handle();
        desc.backends = wgpu::Backends::BROWSER_WEBGPU;
        desc
    });

    let surface = instance
        .create_surface(wgpu::SurfaceTarget::Canvas(canvas.clone()))
        .map_err(|err| js_err(&format!("create_surface: {err}")))?;

    let adapter = instance
        .request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::LowPower,
            compatible_surface: Some(&surface),
            ..Default::default()
        })
        .await
        .map_err(|err| js_err(&format!("request_adapter: {err}")))?;

    let (device, queue) = adapter
        .request_device(&wgpu::DeviceDescriptor {
            label: Some("mc-body/device"),
            required_features: wgpu::Features::empty(),
            // Minimal request so mobile adapters accept it. Raise individual
            // limits only when the chunk pipeline needs them.
            required_limits: wgpu::Limits::downlevel_webgl2_defaults()
                .using_resolution(adapter.limits()),
            experimental_features: wgpu::ExperimentalFeatures::disabled(),
            memory_hints: wgpu::MemoryHints::MemoryUsage,
            trace: wgpu::Trace::Off,
        })
        .await
        .map_err(|err| js_err(&format!("request_device: {err}")))?;

    let mut config = surface
        .get_default_config(&adapter, width, height)
        .ok_or_else(|| js_err("adapter cannot present to this canvas"))?;
    // WebGPU canvas contexts only accept `bgra8unorm`/`rgba8unorm` — there is no
    // sRGB swap-chain format on the web. We therefore keep the block texture
    // array raw sRGB (`Rgba8Unorm`, see `textures.rs`) and let the shader do
    // vanilla-style lighting in sRGB space, which is what Java Minecraft does.
    log(&format!("surface format: {:?}", config.format));
    // Fifo is the only present mode guaranteed everywhere and the
    // battery-friendly choice on iOS.
    config.present_mode = wgpu::PresentMode::Fifo;
    // Two frames in flight: low latency without under-running the compositor.
    config.desired_maximum_frame_latency = 2;
    surface.configure(&device, &config);

    // --- Synthetic world -------------------------------------------------
    // Same store and mesher the Java bridge will drive; only the source of
    // the block IDs is temporary.
    bridge::generate_demo();
    let mesh_start = js_sys::Date::now();
    let world = bridge::mesh_world();
    let mesh_ms = js_sys::Date::now() - mesh_start;

    if world.sections.is_empty() {
        return Err(js_err("demo mesher produced no geometry"));
    }

    let arena = ChunkArena::upload(&device, &queue, &world.sections);

    let reduction = if world.stats.faces > 0 {
        100.0 * (1.0 - world.stats.quads as f64 / world.stats.faces as f64)
    } else {
        0.0
    };
    let stats_text = format!(
        "M2b · textured · {}/{} non-empty sections · {} faces → {} quads ({:.0}% fewer) · {} tris · mesh {:.0} ms",
        arena.slots.iter().filter(|slot| slot.index_count > 0).count(),
        world.stats.sections,
        world.stats.faces,
        world.stats.quads,
        reduction,
        arena.total_indices / 3,
        mesh_ms,
    );
    log(&stats_text.replace('\n', " | "));

    let visible = Vec::with_capacity(arena.len());

    // --- Block textures (vanilla PNGs, uploaded by the page) -------------
    let textures = textures::build(&device, &queue);

    // --- Chunk pipeline --------------------------------------------------
    let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("mc-body/chunks.wgsl"),
        source: wgpu::ShaderSource::Wgsl(include_str!("shader.wgsl").into()),
    });

    let uniform_buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("mc-body/camera"),
        size: UNIFORM_SIZE,
        usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        mapped_at_creation: false,
    });

    let bind_group_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
        label: Some("mc-body/camera.layout"),
        entries: &[
            wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::VERTEX | wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 1,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Texture {
                    sample_type: wgpu::TextureSampleType::Float { filterable: true },
                    view_dimension: wgpu::TextureViewDimension::D2Array,
                    multisampled: false,
                },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 2,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                count: None,
            },
        ],
    });

    let bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("mc-body/camera.bind"),
        layout: &bind_group_layout,
        entries: &[
            wgpu::BindGroupEntry {
                binding: 0,
                resource: uniform_buffer.as_entire_binding(),
            },
            wgpu::BindGroupEntry {
                binding: 1,
                resource: wgpu::BindingResource::TextureView(&textures.view),
            },
            wgpu::BindGroupEntry {
                binding: 2,
                resource: wgpu::BindingResource::Sampler(&textures.sampler),
            },
        ],
    });

    let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some("mc-body/chunks.layout"),
        bind_group_layouts: &[Some(&bind_group_layout)],
        immediate_size: 0,
    });

    let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some("mc-body/chunks"),
        layout: Some(&pipeline_layout),
        vertex: wgpu::VertexState {
            module: &shader,
            entry_point: Some("vs_main"),
            buffers: &[Some(Vertex::layout())],
            compilation_options: Default::default(),
        },
        fragment: Some(wgpu::FragmentState {
            module: &shader,
            entry_point: Some("fs_main"),
            targets: &[Some(wgpu::ColorTargetState {
                format: config.format,
                blend: None,
                write_mask: wgpu::ColorWrites::ALL,
            })],
            compilation_options: Default::default(),
        }),
        primitive: wgpu::PrimitiveState {
            topology: wgpu::PrimitiveTopology::TriangleList,
            front_face: wgpu::FrontFace::Ccw,
            // Quads are wound CCW when viewed from outside (validated in M2a).
            cull_mode: Some(wgpu::Face::Back),
            ..Default::default()
        },
        depth_stencil: Some(wgpu::DepthStencilState {
            format: DEPTH_FORMAT,
            depth_write_enabled: Some(true),
            depth_compare: Some(wgpu::CompareFunction::Less),
            stencil: wgpu::StencilState::default(),
            bias: wgpu::DepthBiasState::default(),
        }),
        multisample: wgpu::MultisampleState::default(),
        multiview_mask: None,
        cache: None,
    });

    let (depth_texture, depth_view) = create_depth(&device, config.width, config.height);
    let start_ms = js_sys::Date::now();

    Ok(Renderer {
        instance,
        canvas,
        surface,
        device,
        queue,
        config,
        pipeline,
        uniform_buffer,
        bind_group,
        textures,
        arena,
        visible,
        frame_index: 0,
        start_ms,
        last_frame_ms: start_ms,
        fps: 0.0,
        draw_ms: 0.0,
        depth_texture,
        depth_view,
        stats_text,
    })
}

fn create_depth(device: &wgpu::Device, width: u32, height: u32) -> (wgpu::Texture, wgpu::TextureView) {
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("mc-body/depth"),
        size: wgpu::Extent3d {
            width: width.max(1),
            height: height.max(1),
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: DEPTH_FORMAT,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
        view_formats: &[],
    });
    let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
    (texture, view)
}

fn start_frame_loop() {
    let slot: Rc<RefCell<Option<Closure<dyn FnMut()>>>> = Rc::new(RefCell::new(None));
    let weak: Weak<RefCell<Option<Closure<dyn FnMut()>>>> = Rc::downgrade(&slot);

    *slot.borrow_mut() = Some(Closure::wrap(Box::new(move || {
        draw_frame();
        if let Some(slot) = weak.upgrade() {
            schedule_frame(&slot);
        }
    }) as Box<dyn FnMut()>));

    FRAME_LOOP.with(|cell| *cell.borrow_mut() = Some(slot.clone()));
    schedule_frame(&slot);
}

fn schedule_frame(slot: &Rc<RefCell<Option<Closure<dyn FnMut()>>>>) {
    let callback = match slot.borrow().as_ref() {
        Some(closure) => closure.as_ref().unchecked_ref::<js_sys::Function>().clone(),
        None => return,
    };
    if let Some(window) = web_sys::window() {
        let _ = window.request_animation_frame(&callback);
    }
}

fn draw_frame() {
    RENDERER.with(|cell| {
        let mut slot = cell.borrow_mut();
        if let Some(renderer) = slot.as_mut() {
            renderer.draw();
        }
    });
}

fn canvas_pixel_size(canvas: &web_sys::HtmlCanvasElement) -> (u32, u32) {
    let dpr = web_sys::window()
        .map(|window| window.device_pixel_ratio())
        .unwrap_or(1.0)
        .clamp(1.0, MAX_DPR);
    let width = (canvas.client_width().max(1) as f64 * dpr)
        .round()
        .max(1.0) as u32;
    let height = (canvas.client_height().max(1) as f64 * dpr)
        .round()
        .max(1.0) as u32;
    (width, height)
}

fn set_status(text: &str) {
    set_element_text("status", text);
}

fn set_stats(text: &str) {
    set_element_text("stats", text);
}

fn set_element_text(id: &str, text: &str) {
    if let Some(document) = web_sys::window().and_then(|window| window.document()) {
        if let Some(element) = document.get_element_by_id(id) {
            element.set_text_content(Some(text));
        }
    }
}

fn report_error(message: &str) {
    web_sys::console::error_1(&JsValue::from_str(message));
    set_status(message);
}

fn log(message: &str) {
    web_sys::console::log_1(&JsValue::from_str(message));
}

fn js_err(message: &str) -> JsValue {
    JsValue::from_str(message)
}
