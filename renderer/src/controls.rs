//! Render-only debug camera.
//!
//! M2b explicitly ships a **free-fly debug camera**: it is not player physics.
//! Walking, jumping, friction, collision and spawn placement stay in the Java
//! Brain (M3); this module only turns input into a pose so the renderer can be
//! inspected on desktop and on a phone.

use std::cell::RefCell;
use std::collections::HashSet;

use wasm_bindgen::prelude::*;
use wasm_bindgen::JsCast;
use web_sys::{HtmlCanvasElement, KeyboardEvent, MouseEvent, PointerEvent};

const WALK_SPEED: f32 = 4.317;
const SPRINT_SPEED: f32 = 5.612;
const FLY_SPEED: f32 = 12.4;
const LOOK_SENSITIVITY: f32 = 0.0022;
const TOUCH_LOOK_SENSITIVITY: f32 = 0.0045;
const EYE_HEIGHT: f32 = 1.62;
const PLAYER_HEIGHT: f32 = 1.8;
const PLAYER_HALF_WIDTH: f32 = 0.3;
const GRAVITY: f32 = 28.0;
const JUMP_SPEED: f32 = 8.6;
const TERMINAL_VELOCITY: f32 = 60.0;

struct TouchState {
    id: i32,
    start_x: f64,
    start_y: f64,
    last_x: f64,
    last_y: f64,
    /// Left-half pointer drives the virtual stick, right-half pointer looks.
    stick: bool,
}

struct Controls {
    pos: [f32; 3],
    yaw: f32,
    pitch: f32,
    keys: HashSet<String>,
    stick: [f32; 2],
    touches: Vec<TouchState>,
    canvas: HtmlCanvasElement,
    velocity_y: f32,
    on_ground: bool,
    flying: bool,
    fly_key_held: bool,
}

thread_local! {
    static CONTROLS: RefCell<Option<Controls>> = const { RefCell::new(None) };
    /// When the Java Brain is authoritative it sends the eye position every
    /// tick. The Body then stops integrating movement entirely: it only owns
    /// the look direction (camera), never the player physics.
    static EXTERNAL_POS: std::cell::Cell<Option<[f32; 3]>> = const { std::cell::Cell::new(None) };
    /// Java also owns yaw/pitch (mouse look runs through its SDL input path).
    /// Stored in Rust camera radians; `None` until the first pose arrives.
    static EXTERNAL_ROT: std::cell::Cell<Option<[f32; 2]>> = const { std::cell::Cell::new(None) };
    /// The hybrid page forwards input through the bridge (and into Java), so the
    /// renderer's own DOM listeners would apply every delta twice. `true` turns
    /// them into no-ops; `body.html` leaves this off and stays standalone.
    static HOST_INPUT: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// Called from the Java bridge with the player's eye position (`getEyeY`, not
/// feet). Once set, the debug free-fly integration is disabled.
pub fn set_external_position(position: [f32; 3]) {
    EXTERNAL_POS.with(|cell| cell.set(Some(position)));
}

pub fn clear_external_position() {
    EXTERNAL_POS.with(|cell| cell.set(None));
}

/// Java sends Minecraft yaw in degrees (0 = south / +Z, 90 = west / -X) and
/// pitch in degrees (positive = looking down). The renderer's camera uses
/// yaw 0 = north / -Z and positive pitch = looking up, so convert once here.
pub fn set_external_rotation(yaw_degrees: f32, pitch_degrees: f32) {
    let yaw = (180.0 - yaw_degrees).to_radians();
    let pitch = (-pitch_degrees).to_radians().clamp(-1.55, 1.55);
    EXTERNAL_ROT.with(|cell| cell.set(Some([yaw, pitch])));
}

pub fn clear_external_rotation() {
    EXTERNAL_ROT.with(|cell| cell.set(None));
}

/// Enables host-page input forwarding. See `HOST_INPUT`.
pub fn set_host_input(hosted: bool) {
    HOST_INPUT.with(|cell| cell.set(hosted));
}

fn host_input() -> bool {
    HOST_INPUT.with(|cell| cell.get())
}

/// Renderer-only input fallback used by `bridge.js` when no Java iframe/SDL
/// shim is present (the deployed `old_mcweb` page). Works regardless of
/// `set_host_input`, so the hybrid page can keep its DOM listeners disabled.
#[wasm_bindgen]
pub fn set_virtual_key(code: &str, down: bool) {
    CONTROLS.with(|cell| {
        if let Some(controls) = cell.borrow_mut().as_mut() {
            if down {
                controls.keys.insert(code.to_string());
            } else {
                controls.keys.remove(code);
            }
        }
    });
}

#[wasm_bindgen]
pub fn set_virtual_stick(x: f32, y: f32) {
    CONTROLS.with(|cell| {
        if let Some(controls) = cell.borrow_mut().as_mut() {
            controls.stick = [x.clamp(-1.0, 1.0), y.clamp(-1.0, 1.0)];
        }
    });
}

#[wasm_bindgen]
pub fn set_virtual_fly(on: bool) {
    CONTROLS.with(|cell| {
        if let Some(controls) = cell.borrow_mut().as_mut() {
            controls.flying = on;
            controls.velocity_y = 0.0;
        }
    });
}

/// Direct look for the renderer when there is no Java brain yet (title/loading)
/// or on the standalone `body.html` harness. Once Java reports a pose every
/// frame, its rotation wins and this only provides sub-frame responsiveness.
pub fn apply_look(dx: f32, dy: f32, touch: bool) {
    let sensitivity = if touch {
        TOUCH_LOOK_SENSITIVITY
    } else {
        LOOK_SENSITIVITY
    };
    CONTROLS.with(|cell| {
        if let Some(controls) = cell.borrow_mut().as_mut() {
            controls.look(dx, dy, sensitivity);
        }
    });
}

impl Controls {
    fn new(canvas: HtmlCanvasElement) -> Self {
        Self {
            // Standing on the south shore, looking north across the island.
            pos: [64.0, 30.0, 118.0],
            yaw: 0.0,
            pitch: -0.22,
            keys: HashSet::new(),
            stick: [0.0, 0.0],
            touches: Vec::new(),
            canvas,
            velocity_y: 0.0,
            on_ground: false,
            flying: false,
            fly_key_held: false,
        }
    }

    fn update(&mut self, dt: f32) {
        let dt = dt.clamp(0.0, 0.05);
        // Java owns physics: follow its eye position and do not integrate here.
        if let Some(position) = EXTERNAL_POS.with(|cell| cell.get()) {
            self.pos = position;
            // Java also owns rotation. Local look (applied for immediacy while
            // waiting for the next Java tick) is replaced by the authoritative
            // mouse-driven rotation here.
            if let Some([yaw, pitch]) = EXTERNAL_ROT.with(|cell| cell.get()) {
                self.yaw = yaw;
                self.pitch = pitch;
            }
            return;
        }

        if self.key("KeyF") {
            if !self.fly_key_held {
                self.flying = !self.flying;
                self.fly_key_held = true;
            }
        } else {
            self.fly_key_held = false;
        }

        let (sin_yaw, cos_yaw) = self.yaw.sin_cos();
        // yaw 0 looks toward -z (north), like standing on the south shore.
        let forward = [-sin_yaw, 0.0, -cos_yaw];
        let right = [cos_yaw, 0.0, -sin_yaw];

        let mut move_x = 0.0f32;
        let mut move_z = 0.0f32;
        if self.key("KeyW") || self.key("ArrowUp") {
            move_z += 1.0;
        }
        if self.key("KeyS") || self.key("ArrowDown") {
            move_z -= 1.0;
        }
        if self.key("KeyD") || self.key("ArrowRight") {
            move_x += 1.0;
        }
        if self.key("KeyA") || self.key("ArrowLeft") {
            move_x -= 1.0;
        }
        // Touch stick adds to the same axes (x = right, z = forward).
        move_x = (move_x + self.stick[0]).clamp(-1.0, 1.0);
        move_z = (move_z + self.stick[1]).clamp(-1.0, 1.0);

        let length = (move_x * move_x + move_z * move_z).sqrt();
        if length > 1.0 {
            move_x /= length;
            move_z /= length;
        }

        let dir_x = forward[0] * move_z + right[0] * move_x;
        let dir_z = forward[2] * move_z + right[2] * move_x;

        if self.flying {
            let speed = FLY_SPEED;
            self.pos[0] += dir_x * speed * dt;
            self.pos[2] += dir_z * speed * dt;
            let mut lift = 0.0;
            if self.key("Space") {
                lift += 1.0;
            }
            if self.key("ControlLeft") || self.key("ControlRight") || self.key("KeyQ") {
                lift -= 1.0;
            }
            self.pos[1] += lift * speed * dt;
            self.pos[1] = self.pos[1].clamp(-16.0, 300.0);
            return;
        }

        // Vanilla-like walking: horizontal axes are resolved separately, then
        // gravity and a jump impulse move the eye. Collision uses the block
        // store above; Java replaces this whole path when connected.
        let sprinting = self.key("ControlLeft") || self.key("ControlRight");
        let speed = if sprinting { SPRINT_SPEED } else { WALK_SPEED };

        let next_x = self.pos[0] + dir_x * speed * dt;
        if !self.collides(next_x, self.pos[1], self.pos[2]) {
            self.pos[0] = next_x;
        }
        let next_z = self.pos[2] + dir_z * speed * dt;
        if !self.collides(self.pos[0], self.pos[1], next_z) {
            self.pos[2] = next_z;
        }

        if self.key("Space") && self.on_ground {
            self.velocity_y = JUMP_SPEED;
            self.on_ground = false;
        }
        self.velocity_y = (self.velocity_y - GRAVITY * dt).max(-TERMINAL_VELOCITY);
        let next_y = self.pos[1] + self.velocity_y * dt;
        if self.collides(self.pos[0], next_y, self.pos[2]) {
            if self.velocity_y < 0.0 {
                self.on_ground = true;
            }
            self.velocity_y = 0.0;
        } else {
            self.pos[1] = next_y;
            self.on_ground = false;
        }
        self.pos[1] = self.pos[1].clamp(-16.0, 300.0);
    }

    /// Axis-aligned player box against the opaque block store.
    fn collides(&self, eye_x: f32, eye_y: f32, eye_z: f32) -> bool {
        let min_x = (eye_x - PLAYER_HALF_WIDTH).floor() as i32;
        let max_x = (eye_x + PLAYER_HALF_WIDTH - 1e-4).floor() as i32;
        let min_y = (eye_y - EYE_HEIGHT).floor() as i32;
        let max_y = (eye_y - EYE_HEIGHT + PLAYER_HEIGHT - 1e-4).floor() as i32;
        let min_z = (eye_z - PLAYER_HALF_WIDTH).floor() as i32;
        let max_z = (eye_z + PLAYER_HALF_WIDTH - 1e-4).floor() as i32;
        for y in min_y..=max_y {
            for z in min_z..=max_z {
                for x in min_x..=max_x {
                    if crate::bridge::is_solid(x, y, z) {
                        return true;
                    }
                }
            }
        }
        false
    }

    fn key(&self, code: &str) -> bool {
        self.keys.contains(code)
    }

    fn look(&mut self, dx: f32, dy: f32, sensitivity: f32) {
        self.yaw -= dx * sensitivity;
        self.pitch = (self.pitch - dy * sensitivity).clamp(-1.55, 1.55);
    }

    fn view_projection(&self, aspect: f32) -> [f32; 16] {
        let (sin_yaw, cos_yaw) = self.yaw.sin_cos();
        let (sin_pitch, cos_pitch) = self.pitch.sin_cos();
        let forward = [-sin_yaw * cos_pitch, sin_pitch, -cos_yaw * cos_pitch];
        let eye = self.pos;
        let target = [
            eye[0] + forward[0],
            eye[1] + forward[1],
            eye[2] + forward[2],
        ];
        let view = crate::math::look_at(eye, target, [0.0, 1.0, 0.0]);
        // Vanilla default FOV is 70 degrees vertical.
        let projection = crate::math::perspective(70.0f32.to_radians(), aspect, 0.05, 512.0);
        crate::math::mul(&projection, &view)
    }
}

/// Installs keyboard/mouse/touch listeners. Called once by `run()`.
pub fn install(canvas: HtmlCanvasElement) {
    CONTROLS.with(|cell| *cell.borrow_mut() = Some(Controls::new(canvas.clone())));

    let window = match web_sys::window() {
        Some(window) => window,
        None => return,
    };

    {
        let closure = Closure::<dyn FnMut(KeyboardEvent)>::new(move |event: KeyboardEvent| {
            if host_input() {
                return;
            }
            let code = event.code();
            let down = event.type_() == "keydown";
            CONTROLS.with(|cell| {
                if let Some(controls) = cell.borrow_mut().as_mut() {
                    if down {
                        controls.keys.insert(code);
                    } else {
                        controls.keys.remove(&code);
                    }
                }
            });
            if down {
                event.prevent_default();
            }
        });
        let _ =
            window.add_event_listener_with_callback("keydown", closure.as_ref().unchecked_ref());
        let _ = window.add_event_listener_with_callback("keyup", closure.as_ref().unchecked_ref());
        closure.forget();
    }

    // Desktop look: pointer lock after a click, and drag-look as a fallback.
    {
        let canvas_for_lock = canvas.clone();
        let closure = Closure::<dyn FnMut(MouseEvent)>::new(move |event: MouseEvent| {
            if host_input() {
                return;
            }
            event.prevent_default();
            let _ = canvas_for_lock.request_pointer_lock();
        });
        let _ =
            canvas.add_event_listener_with_callback("mousedown", closure.as_ref().unchecked_ref());
        closure.forget();
    }
    {
        let closure = Closure::<dyn FnMut(MouseEvent)>::new(move |event: MouseEvent| {
            if host_input() {
                return;
            }
            if event.type_() != "mousemove" {
                return;
            }
            // Only look while the mouse is captured or the left button is held;
            // otherwise the camera would spin as the pointer crosses the page.
            let dragging = event.buttons() & 1 != 0;
            if !dragging && !pointer_locked() {
                return;
            }
            let (dx, dy) = (event.movement_x() as f32, event.movement_y() as f32);
            CONTROLS.with(|cell| {
                if let Some(controls) = cell.borrow_mut().as_mut() {
                    controls.look(dx, dy, LOOK_SENSITIVITY);
                }
            });
        });
        if let Some(document) = window.document() {
            let _ = document
                .add_event_listener_with_callback("mousemove", closure.as_ref().unchecked_ref());
        }
        closure.forget();
    }

    // Touch: left half = stick, right half = look.
    {
        let closure = Closure::<dyn FnMut(PointerEvent)>::new(move |event: PointerEvent| {
            let pointer_type = event.pointer_type();
            if host_input() {
                return;
            }
            if pointer_type != "touch" && pointer_type != "pen" {
                return;
            }
            event.prevent_default();
            let id = event.pointer_id();
            let x = event.client_x() as f64;
            let y = event.client_y() as f64;
            let kind = event.type_();
            CONTROLS.with(|cell| {
                let mut borrowed = cell.borrow_mut();
                let controls = match borrowed.as_mut() {
                    Some(controls) => controls,
                    None => return,
                };
                match kind.as_str() {
                    "pointerdown" => {
                        let width = controls.canvas.client_width().max(1) as f64;
                        controls.touches.push(TouchState {
                            id,
                            start_x: x,
                            start_y: y,
                            last_x: x,
                            last_y: y,
                            stick: x < width * 0.45,
                        });
                    }
                    "pointermove" => {
                        if let Some(touch) = controls.touches.iter_mut().find(|t| t.id == id) {
                            let dx = (x - touch.last_x) as f32;
                            let dy = (y - touch.last_y) as f32;
                            touch.last_x = x;
                            touch.last_y = y;
                            if touch.stick {
                                let max = 56.0;
                                controls.stick[0] =
                                    ((x - touch.start_x) as f32 / max).clamp(-1.0, 1.0);
                                controls.stick[1] =
                                    -((y - touch.start_y) as f32 / max).clamp(-1.0, 1.0);
                            } else {
                                controls.look(dx, dy, TOUCH_LOOK_SENSITIVITY);
                            }
                        }
                    }
                    "pointerup" | "pointercancel" => {
                        let was_stick = controls
                            .touches
                            .iter()
                            .find(|t| t.id == id)
                            .map(|t| t.stick)
                            .unwrap_or(false);
                        controls.touches.retain(|t| t.id != id);
                        if was_stick {
                            controls.stick = [0.0, 0.0];
                        }
                    }
                    _ => {}
                }
            });
        });
        let target: web_sys::EventTarget = window
            .document()
            .map(|document| document.unchecked_into::<web_sys::EventTarget>())
            .unwrap_or_else(|| window.clone().unchecked_into::<web_sys::EventTarget>());
        let _ = target
            .add_event_listener_with_callback("pointerdown", closure.as_ref().unchecked_ref());
        let _ = target
            .add_event_listener_with_callback("pointermove", closure.as_ref().unchecked_ref());
        let _ =
            target.add_event_listener_with_callback("pointerup", closure.as_ref().unchecked_ref());
        let _ = target
            .add_event_listener_with_callback("pointercancel", closure.as_ref().unchecked_ref());
        closure.forget();
    }

    // Safari: keep two-finger gestures from zooming the game canvas.
    if let Some(document) = window.document() {
        let closure = Closure::<dyn FnMut(web_sys::Event)>::new(move |event: web_sys::Event| {
            event.prevent_default();
        });
        let _ = document
            .add_event_listener_with_callback("gesturestart", closure.as_ref().unchecked_ref());
        closure.forget();
    }
}

/// Renderer-only spawn. Places the debug camera on the surface found by
/// `bridge::demo_spawn`. Java, when present, replaces this pose every frame
/// through `set_java_player`, so it never overrides authoritative physics.
pub fn set_spawn(position: [f32; 3]) {
    CONTROLS.with(|cell| {
        if let Some(controls) = cell.borrow_mut().as_mut() {
            controls.pos = position;
            controls.velocity_y = 0.0;
            controls.on_ground = false;
        }
    });
}

pub fn set_spawn_rotation(yaw: f32, pitch: f32) {
    CONTROLS.with(|cell| {
        if let Some(controls) = cell.borrow_mut().as_mut() {
            controls.yaw = yaw;
            controls.pitch = pitch;
        }
    });
}

pub struct CameraPose {
    pub position: [f32; 3],
    pub view_projection: [f32; 16],
}

/// Current pose in the Brain-compatible form (M3 replaces this with the pose
/// Java sends each render tick).
pub fn pose(aspect: f32) -> CameraPose {
    CONTROLS.with(|cell| {
        let borrowed = cell.borrow();
        match borrowed.as_ref() {
            Some(controls) => CameraPose {
                position: controls.pos,
                view_projection: controls.view_projection(aspect),
            },
            None => CameraPose {
                position: [0.0, 60.0, 0.0],
                view_projection: crate::math::perspective(70f32.to_radians(), aspect, 0.05, 512.0),
            },
        }
    })
}

/// Advances the camera by one frame.
/// Debug helper for the headless harness: current eye pose as text.
#[wasm_bindgen]
pub fn debug_pose() -> String {
    CONTROLS.with(|cell| {
        let borrowed = cell.borrow();
        match borrowed.as_ref() {
            Some(controls) => format!(
                "{:.2},{:.2},{:.2} yaw={:.2} pitch={:.2} vy={:.2} ground={} fly={} stick={:.2},{:.2} blocked={}",
                controls.pos[0],
                controls.pos[1],
                controls.pos[2],
                controls.yaw,
                controls.pitch,
                controls.velocity_y,
                controls.on_ground,
                controls.flying,
                controls.stick[0],
                controls.stick[1],
                controls.collides(controls.pos[0], controls.pos[1], controls.pos[2]),
            ),
            None => "none".to_string(),
        }
    })
}

pub fn tick(dt: f32) {
    CONTROLS.with(|cell| {
        if let Some(controls) = cell.borrow_mut().as_mut() {
            controls.update(dt);
        }
    });
}

fn pointer_locked() -> bool {
    web_sys::window()
        .and_then(|window| window.document())
        .map(|document| document.pointer_lock_element().is_some())
        .unwrap_or(false)
}
