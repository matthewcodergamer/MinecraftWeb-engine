// Procedural sky: fullscreen triangle drawn before the chunk pass. World-space
// ray is reconstructed from the inverse view-projection so the sun/moon/stars
// stay fixed while the camera rotates. Colours are computed in Rust from the
// time of day, matching the fog colour for a seamless horizon.
//
// The cloud layer, square sun/moon and star field are the three things that
// make a browser voxel scene read as Minecraft at a glance: the vanilla sky is
// not an empty gradient.

struct Camera {
    view_proj: mat4x4<f32>,
    inv_view_proj: mat4x4<f32>,
    position: vec4<f32>,
    fog_color: vec4<f32>,
    params: vec4<f32>,
    sun_dir: vec4<f32>,
    sky_zenith: vec4<f32>,
    sky_horizon: vec4<f32>,
    sun_color: vec4<f32>,
};

@group(0) @binding(0) var<uniform> camera: Camera;

struct VsOut {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@vertex
fn vs_main(@builtin(vertex_index) index: u32) -> VsOut {
    var positions = array<vec2<f32>, 3>(
        vec2<f32>(-1.0, -1.0),
        vec2<f32>(3.0, -1.0),
        vec2<f32>(-1.0, 3.0),
    );
    let p = positions[index];
    var out: VsOut;
    out.position = vec4<f32>(p, 0.9999, 1.0);
    out.uv = vec2<f32>(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
    return out;
}

fn hash2(p: vec2<f32>) -> f32 {
    return fract(sin(dot(p, vec2<f32>(127.1, 311.7))) * 43758.5453);
}

fn hash3(p: vec3<f32>) -> f32 {
    return fract(sin(dot(p, vec3<f32>(12.9898, 78.233, 37.719))) * 43758.5453);
}

// Blocky cloud field. Each octave is sampled at a floored world cell, so the
// result is constant across a 12x12 block cell and the cloud edge stays as
// hard as vanilla's 12x12 cloud cells instead of becoming a soft fog bank.
fn cloud_density(p: vec2<f32>) -> f32 {
    let coarse = floor(p / 48.0);
    let mid = floor(p / 24.0);
    let fine = floor(p / 12.0);
    return hash2(coarse * 0.73) * 0.55
        + hash2(mid * 1.31 + vec2<f32>(13.0, 7.0)) * 0.30
        + hash2(fine * 2.17 + vec2<f32>(31.0, 19.0)) * 0.15;
}

fn cloud_layer(dir: vec3<f32>) -> vec4<f32> {
    let height = 130.0;
    if (dir.y <= 0.015 || camera.position.y >= height) {
        return vec4<f32>(0.0);
    }
    let t = (height - camera.position.y) / dir.y;
    // Minecraft clouds drift slowly along +x. params.z is the world clock.
    var p = camera.position.xz + dir.xz * t + vec2<f32>(camera.params.z * 1.6, 0.0);
    let coverage = step(0.54, cloud_density(p));
    if (coverage <= 0.0) {
        return vec4<f32>(0.0);
    }
    let day = clamp(camera.sun_dir.w, 0.0, 1.0);
    let horizon_fade = smoothstep(0.015, 0.11, dir.y);
    let underside = mix(0.78, 1.0, smoothstep(0.03, 0.60, dir.y));
    let white = vec3<f32>(1.0, 1.0, 1.0);
    let night = vec3<f32>(0.16, 0.19, 0.28);
    var col = mix(night, white, day) * underside;
    // Warm the cloud tops during sunrise/sunset without turning them orange.
    col = mix(col, camera.sun_color.rgb, (1.0 - day) * 0.18);
    return vec4<f32>(col, horizon_fade * 0.92);
}

// A square disc in the direction sphere, matching the vanilla 30x30 sun and
// 20x20 moon textures instead of the round dot the old shader drew.
fn square_disc(dir: vec3<f32>, center: vec3<f32>, half_size: f32) -> f32 {
    let facing = dot(dir, center);
    if (facing <= 0.0) {
        return 0.0;
    }
    var up_ref = vec3<f32>(0.0, 1.0, 0.0);
    if (abs(center.y) > 0.98) {
        up_ref = vec3<f32>(1.0, 0.0, 0.0);
    }
    let right = normalize(cross(up_ref, center));
    let up = normalize(cross(center, right));
    let u = dot(dir, right) / facing;
    let v = dot(dir, up) / facing;
    let d = max(abs(u), abs(v));
    return 1.0 - smoothstep(half_size * 0.84, half_size, d);
}

@fragment
fn fs_main(in: VsOut) -> @location(0) vec4<f32> {
    let ndc = vec2<f32>(in.uv.x * 2.0 - 1.0, 1.0 - in.uv.y * 2.0);
    let far = camera.inv_view_proj * vec4<f32>(ndc, 1.0, 1.0);
    let dir = normalize(far.xyz / far.w - camera.position.xyz);

    let up = clamp(dir.y, 0.0, 1.0);
    var col = mix(camera.sky_horizon.rgb, camera.sky_zenith.rgb, smoothstep(0.0, 0.62, up));
    // Below the horizon fade to the fog colour so distant terrain melts into it.
    let below = clamp((-dir.y - 0.02) / 0.22, 0.0, 1.0);
    col = mix(col, camera.fog_color.rgb, below * 0.85);

    let day = clamp(camera.sun_dir.w, 0.0, 1.0);
    let sun = normalize(camera.sun_dir.xyz);
    let moon = -sun;

    // Stars: small square pixels, only above the horizon and only at night.
    let star = step(0.9982, hash3(floor(dir * 340.0)))
        * (1.0 - day) * smoothstep(-0.04, 0.22, dir.y);
    col += vec3<f32>(0.86, 0.92, 1.0) * star;

    // Sun and moon are square discs. The glow is separate so the sprite stays
    // crisp at 16x16 texture resolution.
    let sun_dot = max(dot(dir, sun), 0.0);
    col += camera.sun_color.rgb * pow(sun_dot, 180.0) * 0.45 * (0.35 + 0.65 * day);
    col = mix(col, vec3<f32>(1.0, 0.97, 0.86), square_disc(dir, sun, 0.058) * (0.35 + 0.65 * day));
    col += vec3<f32>(0.55, 0.65, 0.95) * pow(max(dot(dir, moon), 0.0), 220.0) * (1.0 - day) * 0.25;
    col = mix(col, vec3<f32>(0.82, 0.88, 1.0), square_disc(dir, moon, 0.045) * (1.0 - day) * 0.95);

    let cloud = cloud_layer(dir);
    col = mix(col, cloud.rgb, cloud.a);

    return vec4<f32>(col, 1.0);
}
