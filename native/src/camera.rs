//! First-person camera and the minimal native movement model.
//!
//! The Java Brain remains the authority for real Minecraft physics. This
//! camera exists so the native renderer is playable before that bridge lands:
//! it walks on the generated heightmap, jumps, and refuses to climb more than
//! one block. The renderer only consumes the resulting view-projection.

use crate::input::Input;
use crate::math;
use crate::world::{self, SECTION, WATER_LEVEL, WORLD_RADIUS};

const EYE_HEIGHT: f32 = 1.62;
const WALK_SPEED: f32 = 4.317;
const SPRINT_SPEED: f32 = 5.612;
const GRAVITY: f32 = 28.0;
const JUMP_SPEED: f32 = 8.6;
const TOUCH_SENSITIVITY: f32 = 0.0032;
const MOUSE_SENSITIVITY: f32 = 0.0024;

pub struct Camera {
    pub position: [f32; 3],
    pub yaw: f32,
    pub pitch: f32,
    velocity_y: f32,
    on_ground: bool,
}

impl Camera {
    pub fn new(spawn: [f32; 3], yaw: f32) -> Self {
        Self {
            position: spawn,
            yaw,
            pitch: -0.18,
            velocity_y: 0.0,
            on_ground: false,
        }
    }

    pub fn eye(&self) -> [f32; 3] {
        [
            self.position[0],
            self.position[1] + EYE_HEIGHT,
            self.position[2],
        ]
    }

    pub fn direction(&self) -> [f32; 3] {
        let cos_pitch = self.pitch.cos();
        [
            self.yaw.sin() * cos_pitch,
            self.pitch.sin(),
            -self.yaw.cos() * cos_pitch,
        ]
    }

    pub fn view_projection(&self, aspect: f32) -> [f32; 16] {
        let eye = self.eye();
        let direction = self.direction();
        let target = [
            eye[0] + direction[0],
            eye[1] + direction[1],
            eye[2] + direction[2],
        ];
        let projection = math::perspective(70f32.to_radians(), aspect, 0.08, 320.0);
        let view = math::look_at(eye, target, [0.0, 1.0, 0.0]);
        math::mul(&projection, &view)
    }

    pub fn update(&mut self, input: &mut Input, dt: f32) {
        let dt = dt.clamp(0.0, 0.05);
        let (look_x, look_y) = input.take_look();
        let sensitivity = if cfg!(target_os = "android") {
            TOUCH_SENSITIVITY
        } else {
            MOUSE_SENSITIVITY
        };
        self.yaw -= look_x * sensitivity;
        self.pitch = (self.pitch - look_y * sensitivity).clamp(-1.54, 1.54);

        let (move_x, move_y) = input.movement();
        let length = (move_x * move_x + move_y * move_y).sqrt();
        if length > 0.001 {
            let nx = move_x / length;
            let ny = move_y / length;
            let forward = [self.yaw.sin(), 0.0, -self.yaw.cos()];
            let right = [self.yaw.cos(), 0.0, self.yaw.sin()];
            let speed = if input.sprint() {
                SPRINT_SPEED
            } else {
                WALK_SPEED
            };
            let old = self.position;
            self.position[0] += (forward[0] * ny + right[0] * nx) * speed * dt;
            self.position[2] += (forward[2] * ny + right[2] * nx) * speed * dt;
            let ground = self.ground_at(self.position[0], self.position[2]);
            if ground > self.position[1] + 1.05 {
                self.position[0] = old[0];
                self.position[2] = old[2];
            }
        }

        let water = self.position[1] < WATER_LEVEL as f32 + 0.9
            && world::terrain_height(
                self.position[0].floor() as i32,
                self.position[2].floor() as i32,
            ) <= WATER_LEVEL;
        if input.take_jump() {
            if self.on_ground {
                self.velocity_y = JUMP_SPEED;
                self.on_ground = false;
            } else if water {
                self.velocity_y = 4.2;
            }
        }

        self.velocity_y -= if water { GRAVITY * 0.22 } else { GRAVITY } * dt;
        if water {
            self.velocity_y = self.velocity_y.max(-2.5);
        }
        self.position[1] += self.velocity_y * dt;

        let ground = self.ground_at(self.position[0], self.position[2]);
        if self.position[1] <= ground {
            self.position[1] = ground;
            self.velocity_y = 0.0;
            self.on_ground = true;
        } else {
            self.on_ground = false;
        }

        let limit = (WORLD_RADIUS * SECTION - 2) as f32;
        self.position[0] = self.position[0].clamp(-limit, limit);
        self.position[2] = self.position[2].clamp(-limit, limit);
        self.position[1] = self.position[1].clamp(1.0, world::WORLD_MAX_Y as f32 - 2.0);
    }

    fn ground_at(&self, x: f32, z: f32) -> f32 {
        let surface = world::terrain_height(x.floor() as i32, z.floor() as i32);
        (surface.max(WATER_LEVEL + 1)) as f32
    }
}
