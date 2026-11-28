//! Minecraft 26.3 — native Vulkan client.
//!
//! This binary is intentionally not a WebView or a browser shell. It creates a
//! native `winit` window, a `wgpu` Vulkan surface, decodes the vanilla block
//! texture archive, generates a section world in Rust, and renders it with the
//! same chunk shader family as the browser body.

mod arena;
mod camera;
mod cull;
mod input;
mod material;
mod math;
mod mesh;
mod renderer;
mod textures;
mod world;

use std::sync::Arc;

use winit::application::ApplicationHandler;
use winit::dpi::LogicalSize;
use winit::event::{ElementState, WindowEvent};
use winit::keyboard::{KeyCode, PhysicalKey};
use winit::event_loop::{ActiveEventLoop, ControlFlow, EventLoop};
use winit::window::{Window, WindowId};

use crate::input::Input;
use crate::renderer::Renderer;

#[derive(Default)]
struct App {
    window: Option<Arc<Window>>,
    renderer: Option<Renderer>,
    input: Input,
}

impl ApplicationHandler for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            return;
        }
        let attributes = Window::default_attributes()
            .with_title("Minecraft Native Vulkan")
            .with_inner_size(LogicalSize::new(1280.0, 720.0));
        let window = match event_loop.create_window(attributes) {
            Ok(window) => Arc::new(window),
            Err(error) => {
                log::error!("create_window failed: {error}");
                event_loop.exit();
                return;
            }
        };
        match pollster::block_on(Renderer::new(window.clone())) {
            Ok(renderer) => {
                log::info!(
                    "native Vulkan renderer ready: {}x{}",
                    renderer.width(),
                    renderer.height()
                );
                self.input.resize(renderer.width() as f64, renderer.height() as f64);
                self.renderer = Some(renderer);
                self.window = Some(window.clone());
                window.request_redraw();
            }
            Err(error) => {
                log::error!("native renderer init failed: {error}");
                event_loop.exit();
            }
        }
    }

    fn window_event(
        &mut self,
        event_loop: &ActiveEventLoop,
        _window_id: WindowId,
        event: WindowEvent,
    ) {
        match event {
            WindowEvent::CloseRequested => event_loop.exit(),
            WindowEvent::Resized(size) => {
                self.input.resize(size.width as f64, size.height as f64);
                if let Some(renderer) = self.renderer.as_mut() {
                    renderer.resize(size);
                }
            }
            WindowEvent::RedrawRequested => {
                if let Some(renderer) = self.renderer.as_mut() {
                    renderer.render(&mut self.input);
                }
            }
            WindowEvent::Touch(touch) => self.input.touch(touch),
            WindowEvent::KeyboardInput { event, .. } => {
                if event.state == ElementState::Pressed
                    && matches!(event.physical_key, PhysicalKey::Code(KeyCode::Escape))
                {
                    event_loop.exit();
                    return;
                }
                if let Some(code) = Input::key_code(&event) {
                    self.input.key(code, event.state);
                }
            }
            WindowEvent::MouseInput { state, button, .. } => {
                self.input.mouse_button(button, state);
            }
            WindowEvent::CursorMoved { position, .. } => {
                self.input.cursor_moved(position.x, position.y);
            }
            WindowEvent::ScaleFactorChanged { .. } => {
                if let Some(window) = self.window.as_ref() {
                    let size = window.inner_size();
                    self.input.resize(size.width as f64, size.height as f64);
                    if let Some(renderer) = self.renderer.as_mut() {
                        renderer.resize(size);
                    }
                }
            }
            _ => {}
        }
    }

    fn about_to_wait(&mut self, _event_loop: &ActiveEventLoop) {
        if let Some(window) = self.window.as_ref() {
            window.request_redraw();
        }
    }
}

pub fn run() {
    let event_loop = EventLoop::new().expect("create winit event loop");
    event_loop.set_control_flow(ControlFlow::Poll);
    let mut app = App::default();
    event_loop.run_app(&mut app).expect("run winit event loop");
}

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn android_main(app: winit::platform::android::activity::AndroidApp) {
    use winit::platform::android::EventLoopBuilderExtAndroid;

    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("mc-native"),
    );
    let event_loop = EventLoop::builder()
        .with_android_app(app)
        .build()
        .expect("create Android event loop");
    event_loop.set_control_flow(ControlFlow::Poll);
    let mut app = App::default();
    event_loop.run_app(&mut app).expect("run Android event loop");
}
