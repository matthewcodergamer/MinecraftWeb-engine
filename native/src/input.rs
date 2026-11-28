//! Native touch and keyboard input.
//!
//! Android uses the familiar two-zone layout: drag on the left half for a
//! virtual stick, drag on the right half to look. Desktop keeps WASD + mouse
//! drag so the same binary can be inspected without a phone.

use std::collections::HashSet;
use std::time::{Duration, Instant};

use winit::event::{ElementState, MouseButton, Touch, TouchPhase};
use winit::keyboard::{KeyCode, PhysicalKey};

/// Two taps inside this window on the look side jump. Bedrock uses a dedicated
/// button; a double-tap keeps the first native build free of a UI overlay while
/// still letting a phone player jump.
const DOUBLE_TAP: Duration = Duration::from_millis(320);

pub struct Input {
    width: f64,
    height: f64,
    keys: HashSet<KeyCode>,
    move_x: f32,
    move_y: f32,
    look_x: f32,
    look_y: f32,
    jump: bool,
    sprint: bool,
    touch_sprint: bool,
    stick_id: Option<u64>,
    stick_start: (f64, f64),
    look_id: Option<u64>,
    look_last: (f64, f64),
    last_tap: Option<Instant>,
    mouse_down: bool,
    mouse_last: Option<(f64, f64)>,
}

impl Default for Input {
    fn default() -> Self {
        Self {
            width: 1.0,
            height: 1.0,
            keys: HashSet::new(),
            move_x: 0.0,
            move_y: 0.0,
            look_x: 0.0,
            look_y: 0.0,
            jump: false,
            sprint: false,
            touch_sprint: false,
            stick_id: None,
            stick_start: (0.0, 0.0),
            look_id: None,
            look_last: (0.0, 0.0),
            last_tap: None,
            mouse_down: false,
            mouse_last: None,
        }
    }
}

impl Input {
    pub fn resize(&mut self, width: f64, height: f64) {
        self.width = width.max(1.0);
        self.height = height.max(1.0);
    }

    pub fn key(&mut self, code: KeyCode, state: ElementState) {
        let pressed = state == ElementState::Pressed;
        if pressed {
            self.keys.insert(code);
            if code == KeyCode::Space {
                self.jump = true;
            }
        } else {
            self.keys.remove(&code);
        }
        self.sprint = self.keys.contains(&KeyCode::ShiftLeft)
            || self.keys.contains(&KeyCode::ShiftRight);
    }

    pub fn touch(&mut self, touch: Touch) {
        let x = touch.location.x;
        let y = touch.location.y;
        match touch.phase {
            TouchPhase::Started => {
                if x < self.width * 0.45 {
                    self.stick_id = Some(touch.id);
                    self.stick_start = (x, y);
                    self.move_x = 0.0;
                    self.move_y = 0.0;
                    self.touch_sprint = false;
                } else {
                    // A quick second tap on the look side is the jump gesture.
                    if self
                        .last_tap
                        .is_some_and(|last| last.elapsed() <= DOUBLE_TAP)
                    {
                        self.jump = true;
                    }
                    self.last_tap = Some(Instant::now());
                    self.look_id = Some(touch.id);
                    self.look_last = (x, y);
                }
            }
            TouchPhase::Moved => {
                if self.stick_id == Some(touch.id) {
                    let dx = ((x - self.stick_start.0) / 64.0).clamp(-1.0, 1.0);
                    let dy = ((y - self.stick_start.1) / 64.0).clamp(-1.0, 1.0);
                    self.move_x = dx as f32;
                    self.move_y = -dy as f32;
                    self.touch_sprint = dx * dx + dy * dy > 0.85;
                } else if self.look_id == Some(touch.id) {
                    self.look_x += (x - self.look_last.0) as f32;
                    self.look_y += (y - self.look_last.1) as f32;
                    self.look_last = (x, y);
                }
            }
            TouchPhase::Ended | TouchPhase::Cancelled => {
                if self.stick_id == Some(touch.id) {
                    self.stick_id = None;
                    self.move_x = 0.0;
                    self.move_y = 0.0;
                    self.touch_sprint = false;
                }
                if self.look_id == Some(touch.id) {
                    self.look_id = None;
                }
            }
        }
    }

    pub fn mouse_button(&mut self, button: MouseButton, state: ElementState) {
        if button == MouseButton::Left {
            self.mouse_down = state == ElementState::Pressed;
            if !self.mouse_down {
                self.mouse_last = None;
            }
        }
    }

    pub fn cursor_moved(&mut self, x: f64, y: f64) {
        if !self.mouse_down {
            self.mouse_last = Some((x, y));
            return;
        }
        if let Some((last_x, last_y)) = self.mouse_last {
            self.look_x += (x - last_x) as f32;
            self.look_y += (y - last_y) as f32;
        }
        self.mouse_last = Some((x, y));
    }

    pub fn movement(&self) -> (f32, f32) {
        let mut x = 0.0;
        let mut y = 0.0;
        if self.keys.contains(&KeyCode::KeyA) {
            x -= 1.0;
        }
        if self.keys.contains(&KeyCode::KeyD) {
            x += 1.0;
        }
        if self.keys.contains(&KeyCode::KeyW) {
            y += 1.0;
        }
        if self.keys.contains(&KeyCode::KeyS) {
            y -= 1.0;
        }
        if x != 0.0 || y != 0.0 {
            (x, y)
        } else {
            (self.move_x, self.move_y)
        }
    }

    pub fn sprint(&self) -> bool {
        self.sprint || self.touch_sprint
    }

    pub fn take_look(&mut self) -> (f32, f32) {
        let out = (self.look_x, self.look_y);
        self.look_x = 0.0;
        self.look_y = 0.0;
        out
    }

    pub fn take_jump(&mut self) -> bool {
        std::mem::take(&mut self.jump)
    }

    pub fn key_code(event: &winit::event::KeyEvent) -> Option<KeyCode> {
        match event.physical_key {
            PhysicalKey::Code(code) => Some(code),
            PhysicalKey::Unidentified(_) => None,
        }
    }
}
