// mc-body chunk shader — vanilla block texture array, directional face shading,
// smooth sky/block light + AO and distance fog. One texture array (not an atlas)
// lets a greedily merged quad repeat its tile with the sampler, which is exactly
// how vanilla repeats block textures across a face.

struct Camera {
    view_proj: mat4x4<f32>,
    inv_view_proj: mat4x4<f32>,
    // xyz = eye position, w unused
    position: vec4<f32>,
    // rgb = horizon/fog colour, a = fog density
    fog_color: vec4<f32>,
    // x = fog start, y = fog end, z = seconds, w = sky light intensity 0..1
    params: vec4<f32>,
    // xyz = sun direction, w = day factor 0..1
    sun_dir: vec4<f32>,
    sky_zenith: vec4<f32>,
    sky_horizon: vec4<f32>,
    sun_color: vec4<f32>,
};

@group(0) @binding(0) var<uniform> camera: Camera;
@group(0) @binding(1) var blocks: texture_2d_array<f32>;
@group(0) @binding(2) var block_sampler: sampler;

struct VertexInput {
    @location(0) position: vec3<f32>,
    @location(1) uv: vec2<f32>,
    @location(2) layer: u32,
    @location(3) shade: u32,
    // Packed sky << 4 | block, both nibbles 0..15. f32 so it interpolates.
    @location(4) light: f32,
    // 0..255, 255 = fully open.
    @location(5) ao: u32,
    // 0 = opaque, 1 = cutout, 2 = blended (water/ice).
    @location(6) kind: u32,
};

struct VertexOutput {
    @builtin(position) clip_position: vec4<f32>,
    @location(0) uv: vec2<f32>,
    @location(1) @interpolate(flat) layer: u32,
    // Face shade is constant across a greedy quad; flat interpolation avoids
    // inventing a gradient between identical vertices.
    @location(2) @interpolate(flat) shade: f32,
    @location(3) world: vec3<f32>,
    @location(4) light: f32,
    @location(5) ao: f32,
    @location(6) @interpolate(flat) kind: u32,
};

@vertex
fn vs_main(input: VertexInput) -> VertexOutput {
    var output: VertexOutput;
    output.clip_position = camera.view_proj * vec4<f32>(input.position, 1.0);
    output.uv = input.uv;
    output.layer = input.layer;
    output.shade = f32(input.shade) / 255.0;
    output.world = input.position;
    output.light = input.light;
    output.ao = f32(input.ao) / 255.0;
    output.kind = input.kind;
    return output;
}

// Vanilla lightmap curve: pow(0.8, 15 - level), then a warm shift at low
// levels. Sky light is scaled by the time-of-day intensity; block light stays
// full at night.
fn lightmap(sky: f32, block: f32) -> vec3<f32> {
    let level = max(block, sky * camera.params.w);
    // A floor of 0.12 keeps caves readable without flattening the contrast.
    let l = clamp(max(level, 0.12), 0.0, 1.0);
    let brightness = pow(0.8, 15.0 - l * 15.0);
    let warm = mix(vec3<f32>(1.0, 0.82, 0.62), vec3<f32>(1.0), l);
    return warm * brightness;
}

@fragment
fn fs_main(input: VertexOutput) -> @location(0) vec4<f32> {
    let texel = textureSample(blocks, block_sampler, input.uv, i32(input.layer));
    // Cutout pass (leaves, flowers, glass, saplings): discard transparent
    // texels. Opaque textures have alpha 1 and never hit this branch.
    if (texel.a < 0.5) {
        discard;
    }
    // Blended pass (water/ice): keep the texture's alpha, capped so the
    // surface stays one vanilla-like layer instead of stacking to opaque.
    var alpha = 1.0;
    if (input.kind == 2u) {
        alpha = clamp(texel.a, 0.0, 0.85);
    }

    let packed = u32(round(input.light));
    let sky = f32((packed >> 4u) & 15u) / 15.0;
    let block = f32(packed & 15u) / 15.0;
    var color = texel.rgb * input.shade * input.ao * lightmap(sky, block);

    let distance = length(input.world - camera.position.xyz);
    // fog_color.a lets the Java Brain disable fog (nether/end, or a custom
    // dimension) without rebuilding the pipeline.
    let fog = smoothstep(camera.params.x, camera.params.y, distance) * camera.fog_color.a;
    color = mix(color, camera.fog_color.rgb, fog);
    return vec4<f32>(color, alpha);
}
