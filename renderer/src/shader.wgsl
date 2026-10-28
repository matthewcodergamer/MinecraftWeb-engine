// mc-body chunk shader — vanilla block texture array, directional face shading,
// distance fog. One texture array (not an atlas) lets a greedily merged quad
// repeat its tile with the sampler, which is exactly how vanilla repeats block
// textures across a face.

struct Camera {
    view_proj: mat4x4<f32>,
    // xyz = eye position, w unused
    position: vec4<f32>,
    // rgb = horizon/sky colour, a = fog density
    fog_color: vec4<f32>,
    // x = fog start, y = fog end, z = seconds, w unused
    params: vec4<f32>,
};

@group(0) @binding(0) var<uniform> camera: Camera;
@group(0) @binding(1) var blocks: texture_2d_array<f32>;
@group(0) @binding(2) var block_sampler: sampler;

struct VertexInput {
    @location(0) position: vec3<f32>,
    @location(1) uv: vec2<f32>,
    @location(2) layer: u32,
    @location(3) shade: u32,
};

struct VertexOutput {
    @builtin(position) clip_position: vec4<f32>,
    @location(0) uv: vec2<f32>,
    @location(1) @interpolate(flat) layer: u32,
    @location(2) shade: f32,
    @location(3) world: vec3<f32>,
};

@vertex
fn vs_main(input: VertexInput) -> VertexOutput {
    var output: VertexOutput;
    output.clip_position = camera.view_proj * vec4<f32>(input.position, 1.0);
    output.uv = input.uv;
    output.layer = input.layer;
    output.shade = f32(input.shade) / 255.0;
    output.world = input.position;
    return output;
}

@fragment
fn fs_main(input: VertexOutput) -> @location(0) vec4<f32> {
    let texel = textureSample(blocks, block_sampler, input.uv, i32(input.layer));
    // Vanilla cutout alpha test (> 0.1) rather than a hard 0.5, so mip-mapped
    // leaves keep their coverage at distance instead of dissolving into holes.
    if (texel.a < 0.1) {
        discard;
    }
    // Vanilla: texel * face shade * lightmap. The demo has full sky light, so
    // face shading is the whole light term.
    var color = texel.rgb * input.shade;
    let distance = length(input.world - camera.position.xyz);
    let fog = smoothstep(camera.params.x, camera.params.y, distance);
    color = mix(color, camera.fog_color.rgb, fog);
    return vec4<f32>(color, 1.0);
}
