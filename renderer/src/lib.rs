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
mod lighting;
mod material;
mod math;
mod mesh;
mod noise;
mod textures;
mod worldgen;

use std::cell::RefCell;
use std::rc::{Rc, Weak};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

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

/// Full day/night cycle used by the renderer-only sky. Vanilla is 20 minutes;
/// a shorter cycle makes the feature visible while testing on a phone.
const DAY_SECONDS: f32 = 600.0;
const UNIFORM_SIZE: u64 = 240;

/// Default view distance in chunks. The Java brain streams sections; this caps
/// how far the Body will *draw* them, which is the memory/bandwidth lever on
/// iOS Safari (iPhone 11 has a ~1.5 GB per-tab budget). Overridden from JS via
/// `set_render_distance`, which the host calls with 4 on phones.
const DEFAULT_RENDER_DISTANCE: u32 = 8;
static RENDER_DISTANCE: AtomicU32 = AtomicU32::new(DEFAULT_RENDER_DISTANCE);

/// Post-processing is always compiled in; the host can bypass it for A/B
/// debugging (`?post=0`). The scene is rendered to an offscreen Rgba8 target
/// and the fullscreen pass in `post.wgsl` grades it onto the swap chain.
static POST_ENABLED: AtomicBool = AtomicBool::new(true);

thread_local! {
    /// Optional external time override (0 = sunrise, 0.25 = noon). Java can
    /// send its world time here; otherwise the renderer runs its own cycle.
    static TIME_OVERRIDE: std::cell::Cell<Option<f32>> = const { std::cell::Cell::new(None) };
}

/// Sets the sky/sun phase from an external clock. `fraction` is one full day
/// (0 = sunrise, 0.25 = noon, 0.5 = sunset, 0.75 = midnight).
#[wasm_bindgen]
pub fn set_time_of_day(fraction: f32) {
    let wrapped = fraction.rem_euclid(1.0);
    TIME_OVERRIDE.with(|cell| cell.set(Some(wrapped)));
}

#[wasm_bindgen]
pub fn clear_time_of_day() {
    TIME_OVERRIDE.with(|cell| cell.set(None));
}

#[wasm_bindgen]
pub fn set_post_enabled(on: bool) {
    POST_ENABLED.store(on, Ordering::Relaxed);
}

#[wasm_bindgen]
pub fn post_enabled() -> bool {
    POST_ENABLED.load(Ordering::Relaxed)
}

/// Chunk (16 m) view distance. Clamped so a bad host value cannot turn the
/// fog and the cull radius into nonsense.
#[wasm_bindgen]
pub fn set_render_distance(chunks: u32) {
    RENDER_DISTANCE.store(chunks.clamp(2, 32), Ordering::Relaxed);
}

#[wasm_bindgen]
pub fn render_distance() -> u32 {
    RENDER_DISTANCE.load(Ordering::Relaxed)
}

struct SkyState {
    zenith: [f32; 3],
    horizon: [f32; 3],
    sun_color: [f32; 3],
    sun_dir: [f32; 3],
    intensity: f32,
    day: f32,
}

fn smoothstep(edge0: f32, edge1: f32, x: f32) -> f32 {
    let t = ((x - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

fn mix3(a: [f32; 3], b: [f32; 3], t: f32) -> [f32; 3] {
    [
        a[0] + (b[0] - a[0]) * t,
        a[1] + (b[1] - a[1]) * t,
        a[2] + (b[2] - a[2]) * t,
    ]
}

/// Vanilla-ish day/night palette. `time` 0 = sunrise, 0.25 = noon,
/// 0.5 = sunset, 0.75 = midnight.
fn sky_state(time: f32) -> SkyState {
    let angle = time * std::f32::consts::TAU;
    let elevation = angle.sin();
    let day = smoothstep(-0.08, 0.24, elevation);
    let sunset = (1.0 - elevation.abs() / 0.30).clamp(0.0, 1.0) * day.max(0.15);

    let night_zenith = [0.010, 0.018, 0.052];
    let night_horizon = [0.030, 0.052, 0.105];
    let day_zenith = [0.30, 0.54, 1.00];
    let day_horizon = [0.70, 0.83, 1.00];

    let mut zenith = mix3(night_zenith, day_zenith, day);
    let mut horizon = mix3(night_horizon, day_horizon, day);
    zenith = mix3(zenith, [0.35, 0.26, 0.46], sunset * 0.30);
    horizon = mix3(horizon, [1.00, 0.42, 0.18], sunset * 0.70);

    let sun_color = mix3([1.0, 0.42, 0.16], [1.0, 0.95, 0.86], day);
    let sun_dir = [angle.cos() * 0.82, elevation, angle.cos() * 0.36];
    let length = (sun_dir[0] * sun_dir[0] + sun_dir[1] * sun_dir[1] + sun_dir[2] * sun_dir[2])
        .sqrt()
        .max(0.001);
    SkyState {
        zenith,
        horizon,
        sun_color,
        sun_dir: [
            sun_dir[0] / length,
            sun_dir[1] / length,
            sun_dir[2] / length,
        ],
        intensity: 0.16 + 0.84 * day,
        day,
    }
}

struct Renderer {
    instance: wgpu::Instance,
    canvas: web_sys::HtmlCanvasElement,
    surface: wgpu::Surface<'static>,
    device: wgpu::Device,
    queue: wgpu::Queue,
    config: wgpu::SurfaceConfiguration,

    pipeline: wgpu::RenderPipeline,
    sky_pipeline: wgpu::RenderPipeline,
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
    first_frame: bool,
    horizon: [f32; 3],

    #[allow(dead_code)] // the view keeps the texture alive; handle is owned here
    depth_texture: wgpu::Texture,
    depth_view: wgpu::TextureView,

    // --- Post-processing -------------------------------------------------
    // The chunk pass renders into `scene_view`; `post_pipeline` samples it and
    // writes the graded result to the swap chain.
    post_pipeline: wgpu::RenderPipeline,
    post_layout: wgpu::BindGroupLayout,
    post_bind_group: wgpu::BindGroup,
    post_sampler: wgpu::Sampler,
    #[allow(dead_code)]
    scene_texture: wgpu::Texture,
    scene_view: wgpu::TextureView,

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

        // The post pass samples a same-size copy of the frame.
        let (scene_texture, scene_view) =
            create_scene_target(&self.device, width, height, self.config.format);
        self.post_bind_group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("mc-body/post.bind"),
            layout: &self.post_layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(&scene_view),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(&self.post_sampler),
                },
            ],
        });
        self.scene_texture = scene_texture;
        self.scene_view = scene_view;
    }

    fn update_camera(&mut self) -> ([f32; 16], [f32; 3]) {
        let aspect = self.config.width.max(1) as f32 / self.config.height.max(1) as f32;
        let now = js_sys::Date::now();
        let dt = ((now - self.last_frame_ms) * 0.001) as f32;
        self.last_frame_ms = now;
        self.fps = if dt > 0.0 { 1.0 / dt } else { 0.0 };
        controls::tick(dt);

        let pose = controls::pose(aspect);
        // Fog tracks the view distance so a 4-chunk phone build fades out at
        // 64 m instead of drawing nothing and fogging at 192 m.
        let view_blocks = RENDER_DISTANCE.load(Ordering::Relaxed) as f32 * 16.0;
        let elapsed = ((now - self.start_ms) * 0.001) as f32;
        let time = TIME_OVERRIDE
            .with(|cell| cell.get())
            .unwrap_or_else(|| (0.20 + elapsed / DAY_SECONDS).fract());
        let sky = sky_state(time);

        let mut uniform = [0u8; UNIFORM_SIZE as usize];
        uniform[0..64].copy_from_slice(&math::to_bytes(&pose.view_projection));
        uniform[64..128].copy_from_slice(&math::to_bytes(&math::invert(&pose.view_projection)));
        let mut write4 = |slot: usize, value: [f32; 4]| {
            let offset = slot * 16;
            for (i, component) in value.iter().enumerate() {
                uniform[offset + i * 4..offset + i * 4 + 4]
                    .copy_from_slice(&component.to_le_bytes());
            }
        };
        write4(
            8,
            [pose.position[0], pose.position[1], pose.position[2], 0.0],
        );
        write4(9, [sky.horizon[0], sky.horizon[1], sky.horizon[2], 1.0]);
        write4(
            10,
            [
                view_blocks * 0.55,
                view_blocks * 0.98,
                elapsed,
                sky.intensity,
            ],
        );
        write4(
            11,
            [sky.sun_dir[0], sky.sun_dir[1], sky.sun_dir[2], sky.day],
        );
        write4(12, [sky.zenith[0], sky.zenith[1], sky.zenith[2], 1.0]);
        write4(13, [sky.horizon[0], sky.horizon[1], sky.horizon[2], 1.0]);
        write4(
            14,
            [sky.sun_color[0], sky.sun_color[1], sky.sun_color[2], 1.0],
        );
        self.queue.write_buffer(&self.uniform_buffer, 0, &uniform);
        self.horizon = sky.horizon;
        (pose.view_projection, pose.position)
    }

    fn draw(&mut self) {
        self.sync_size();
        // First real Java section arrives: retire the synthetic demo arena once.
        // The dirty queue below rebuilds the Brain's sections incrementally.
        if bridge::take_reset() {
            self.arena.clear();
            self.visible.clear();
        }
        // A block edit or a Java section invalidates the renderer-only BFS
        // light field. Recompute it once, before the dirty queue is consumed.
        bridge::rebuild_light_if_stale();
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
        let (view_projection, eye) = self.update_camera();

        let frustum = Frustum::from_view_projection(&view_projection);
        // Radial chunk-distance cull in addition to the frustum. Sections past
        // the view distance are never bound, so neither their vertex buffers
        // nor their texture fetches cost the phone anything.
        let view_blocks = RENDER_DISTANCE.load(Ordering::Relaxed) as f32 * 16.0;
        let view_blocks_sq = view_blocks * view_blocks;
        self.visible.clear();
        let mut visible_triangles = 0u32;
        for (index, slot) in self.arena.slots.iter().enumerate() {
            if slot.index_count == 0 || !frustum.intersects_aabb(slot.min, slot.max) {
                continue;
            }
            let cx = (slot.min[0] + slot.max[0]) * 0.5 - eye[0];
            let cy = (slot.min[1] + slot.max[1]) * 0.5 - eye[1];
            let cz = (slot.min[2] + slot.max[2]) * 0.5 - eye[2];
            if cx * cx + cy * cy + cz * cz > view_blocks_sq {
                continue;
            }
            self.visible.push(index);
            visible_triangles += slot.index_count / 3;
        }

        self.frame_index += 1;
        if self.frame_index % 15 == 1 {
            set_stats(&format!(
                "{} · {:.0} fps · {:.1} ms\n{}/{} sections · {}/{} tris drawn",
                self.stats_text,
                self.fps,
                self.draw_ms,
                self.visible.len(),
                self.arena
                    .slots
                    .iter()
                    .filter(|s| s.index_count > 0)
                    .count(),
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

        let swap_view = frame
            .texture
            .create_view(&wgpu::TextureViewDescriptor::default());
        let post = POST_ENABLED.load(Ordering::Relaxed);
        // Without post-processing the chunk pass draws straight to the swap
        // chain; with it, it draws into the offscreen target below.
        let scene_view = if post { &self.scene_view } else { &swap_view };
        let mut encoder = self
            .device
            .create_command_encoder(&wgpu::CommandEncoderDescriptor {
                label: Some("mc-body/frame"),
            });
        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("mc-body/chunks"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: scene_view,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color {
                            r: self.horizon[0] as f64,
                            g: self.horizon[1] as f64,
                            b: self.horizon[2] as f64,
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
            // Sky first: it fills every pixel without depth writes, then the
            // chunk pipeline overdraws it where terrain exists.
            pass.set_pipeline(&self.sky_pipeline);
            pass.set_bind_group(0, &self.bind_group, &[]);
            pass.draw(0..3, 0..1);

            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.bind_group, &[]);
            for &index in &self.visible {
                let slot = &self.arena.slots[index];
                pass.set_vertex_buffer(0, slot.vertex_buffer.slice(..));
                pass.set_index_buffer(slot.index_buffer.slice(..), wgpu::IndexFormat::Uint32);
                pass.draw_indexed(0..slot.index_count, 0, 0..1);
            }
        }

        if post {
            // Fullscreen triangle: colour-grade the scene onto the swap chain.
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("mc-body/post"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &swap_view,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
            pass.set_pipeline(&self.post_pipeline);
            pass.set_bind_group(0, &self.post_bind_group, &[]);
            pass.draw(0..3, 0..1);
        }

        self.queue.submit(Some(encoder.finish()));
        self.queue.present(frame);
        self.draw_ms = (js_sys::Date::now() - frame_start) as f32;
        if !self.first_frame {
            self.first_frame = true;
            set_ready_flag();
        }
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
                set_error_flag(&message);
            }
        }
    });
}

/// Java Brain → Body player pose. `y` is the eye height (`LocalPlayer.getEyeY`),
/// so the Rust camera follows Java's collision/gravity result exactly.
#[wasm_bindgen]
pub fn set_java_player(x: f32, y: f32, z: f32) {
    controls::set_external_position([x, y, z]);
}

/// Returns the camera to the standalone free-fly debug pose.
#[wasm_bindgen]
pub fn clear_java_player() {
    controls::clear_external_position();
    controls::clear_external_rotation();
}

/// Java Brain → Body rotation, in Minecraft degrees (`getYRot` / `getXRot`).
/// The renderer converts to its own camera convention in `controls`.
#[wasm_bindgen]
pub fn set_java_rotation(yaw_degrees: f32, pitch_degrees: f32) {
    controls::set_external_rotation(yaw_degrees, pitch_degrees);
}

/// Parent page → camera, used for mouse/touch look. `touch` selects the higher
/// mobile sensitivity. Ignored for rotation once Java reports its pose.
#[wasm_bindgen]
pub fn apply_look(dx: f32, dy: f32, touch: bool) {
    controls::apply_look(dx, dy, touch);
}

/// The hybrid page owns input forwarding (bridge -> SDL/Java), so disable the
/// renderer's duplicate DOM listeners on its visible canvas.
#[wasm_bindgen]
pub fn set_host_input(hosted: bool) {
    controls::set_host_input(hosted);
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
    // Stand on the surface instead of the old fixed y = 30 (which floated ~20
    // blocks over the island). Java overwrites this pose when it is running.
    let (spawn, spawn_yaw, spawn_pitch) = bridge::demo_spawn_pose();
    controls::set_spawn(spawn);
    controls::set_spawn_rotation(spawn_yaw, spawn_pitch);
    let mesh_start = js_sys::Date::now();
    let world = bridge::mesh_world();
    bridge::clear_dirty();
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

    // --- Sky pipeline ----------------------------------------------------
    let sky_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("mc-body/sky.wgsl"),
        source: wgpu::ShaderSource::Wgsl(include_str!("sky.wgsl").into()),
    });
    let sky_pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some("mc-body/sky"),
        layout: Some(&pipeline_layout),
        vertex: wgpu::VertexState {
            module: &sky_shader,
            entry_point: Some("vs_main"),
            buffers: &[],
            compilation_options: Default::default(),
        },
        fragment: Some(wgpu::FragmentState {
            module: &sky_shader,
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
            cull_mode: None,
            ..Default::default()
        },
        depth_stencil: Some(wgpu::DepthStencilState {
            format: DEPTH_FORMAT,
            depth_write_enabled: Some(false),
            depth_compare: Some(wgpu::CompareFunction::Always),
            stencil: wgpu::StencilState::default(),
            bias: wgpu::DepthBiasState::default(),
        }),
        multisample: wgpu::MultisampleState::default(),
        multiview_mask: None,
        cache: None,
    });

    // --- Post-processing pipeline ---------------------------------------
    // `post.wgsl` is a placeholder colour grade; drop the Complementary
    // Shader WGSL port in here later without touching the Rust side.
    let post_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("mc-body/post.wgsl"),
        source: wgpu::ShaderSource::Wgsl(include_str!("post.wgsl").into()),
    });

    let post_sampler = device.create_sampler(&wgpu::SamplerDescriptor {
        label: Some("mc-body/post.sampler"),
        address_mode_u: wgpu::AddressMode::ClampToEdge,
        address_mode_v: wgpu::AddressMode::ClampToEdge,
        mag_filter: wgpu::FilterMode::Linear,
        min_filter: wgpu::FilterMode::Linear,
        ..Default::default()
    });

    let post_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
        label: Some("mc-body/post.layout"),
        entries: &[
            wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Texture {
                    sample_type: wgpu::TextureSampleType::Float { filterable: true },
                    view_dimension: wgpu::TextureViewDimension::D2,
                    multisampled: false,
                },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 1,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                count: None,
            },
        ],
    });

    let post_pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some("mc-body/post.pipeline.layout"),
        bind_group_layouts: &[Some(&post_layout)],
        immediate_size: 0,
    });

    let post_pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some("mc-body/post"),
        layout: Some(&post_pipeline_layout),
        vertex: wgpu::VertexState {
            module: &post_shader,
            entry_point: Some("vs_main"),
            buffers: &[],
            compilation_options: Default::default(),
        },
        fragment: Some(wgpu::FragmentState {
            module: &post_shader,
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
            ..Default::default()
        },
        // The fullscreen triangle is screen-space: no depth, no culling.
        depth_stencil: None,
        multisample: wgpu::MultisampleState::default(),
        multiview_mask: None,
        cache: None,
    });

    let (scene_texture, scene_view) =
        create_scene_target(&device, config.width, config.height, config.format);
    let post_bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("mc-body/post.bind"),
        layout: &post_layout,
        entries: &[
            wgpu::BindGroupEntry {
                binding: 0,
                resource: wgpu::BindingResource::TextureView(&scene_view),
            },
            wgpu::BindGroupEntry {
                binding: 1,
                resource: wgpu::BindingResource::Sampler(&post_sampler),
            },
        ],
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
        sky_pipeline,
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
        first_frame: false,
        horizon: [0.47, 0.63, 0.84],
        depth_texture,
        depth_view,
        post_pipeline,
        post_layout,
        post_bind_group,
        post_sampler,
        scene_texture,
        scene_view,
        stats_text,
    })
}

fn create_scene_target(
    device: &wgpu::Device,
    width: u32,
    height: u32,
    format: wgpu::TextureFormat,
) -> (wgpu::Texture, wgpu::TextureView) {
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("mc-body/scene"),
        size: wgpu::Extent3d {
            width: width.max(1),
            height: height.max(1),
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        // Match the swap chain exactly: a BGRA surface cannot be rendered into
        // an RGBA target, and the chunk pipeline is created for `config.format`.
        format,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING,
        view_formats: &[],
    });
    let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
    (texture, view)
}

fn create_depth(
    device: &wgpu::Device,
    width: u32,
    height: u32,
) -> (wgpu::Texture, wgpu::TextureView) {
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
    let width = (canvas.client_width().max(1) as f64 * dpr).round().max(1.0) as u32;
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

fn set_ready_flag() {
    if let Some(window) = web_sys::window() {
        let _ = js_sys::Reflect::set(&window, &JsValue::from_str("__mcBodyReady"), &JsValue::TRUE);
        let _ = js_sys::Reflect::set(
            &window,
            &JsValue::from_str("__mcBodyReadyAt"),
            &JsValue::from_f64(js_sys::Date::now()),
        );
    }
}

fn set_error_flag(message: &str) {
    if let Some(window) = web_sys::window() {
        let _ = js_sys::Reflect::set(
            &window,
            &JsValue::from_str("__mcBodyError"),
            &JsValue::from_str(message),
        );
    }
}

fn log(message: &str) {
    web_sys::console::log_1(&JsValue::from_str(message));
}

fn js_err(message: &str) -> JsValue {
    JsValue::from_str(message)
}
