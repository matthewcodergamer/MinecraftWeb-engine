//! Minimal column-major 4x4 math for the camera (WGSL expects column-major).

pub fn perspective(fov_y_radians: f32, aspect: f32, near: f32, far: f32) -> [f32; 16] {
    let f = 1.0 / (fov_y_radians * 0.5).tan();
    let mut m = [0.0f32; 16];
    m[0] = f / aspect;
    m[5] = f;
    // WebGPU clip space: z in [0, 1] (right-handed view space).
    m[10] = far / (near - far);
    m[11] = -1.0;
    m[14] = near * far / (near - far);
    m
}

pub fn look_at(eye: [f32; 3], target: [f32; 3], up: [f32; 3]) -> [f32; 16] {
    let f = normalize(sub(target, eye));
    let s = normalize(cross(f, up));
    let u = cross(s, f);

    let mut m = [0.0f32; 16];
    m[0] = s[0];
    m[1] = u[0];
    m[2] = -f[0];
    m[4] = s[1];
    m[5] = u[1];
    m[6] = -f[1];
    m[8] = s[2];
    m[9] = u[2];
    m[10] = -f[2];
    m[12] = -dot(s, eye);
    m[13] = -dot(u, eye);
    m[14] = dot(f, eye);
    m[15] = 1.0;
    m
}

pub fn mul(a: &[f32; 16], b: &[f32; 16]) -> [f32; 16] {
    let mut out = [0.0f32; 16];
    for col in 0..4 {
        for row in 0..4 {
            let mut sum = 0.0;
            for k in 0..4 {
                sum += a[k * 4 + row] * b[col * 4 + k];
            }
            out[col * 4 + row] = sum;
        }
    }
    out
}

pub fn to_bytes(m: &[f32; 16]) -> [u8; 64] {
    let mut out = [0u8; 64];
    for (i, value) in m.iter().enumerate() {
        out[i * 4..i * 4 + 4].copy_from_slice(&value.to_le_bytes());
    }
    out
}

fn sub(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}

fn cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}

fn dot(a: [f32; 3], b: [f32; 3]) -> f32 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

fn normalize(v: [f32; 3]) -> [f32; 3] {
    let length = dot(v, v).sqrt();
    if length <= f32::EPSILON {
        [0.0, 0.0, 0.0]
    } else {
        [v[0] / length, v[1] / length, v[2] / length]
    }
}
