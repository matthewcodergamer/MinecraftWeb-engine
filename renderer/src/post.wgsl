// mc-body post-processing — placeholder colour grade.
//
// This is the slot the Complementary Shader WGSL port will replace: the chunk
// pass renders into an offscreen Rgba8Unorm target, this fullscreen triangle
// samples it, and the result is written to the swap chain. Keeping the
// interface to a single colour texture means a full shader port (tonemap,
// bloom, shadows, water) can be dropped in without touching lib.rs — only the
// bind group layout has to grow if it needs depth or extra buffers.

@group(0) @binding(0) var scene_tex: texture_2d<f32>;
@group(0) @binding(1) var scene_sampler: sampler;

struct VsOut {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

// Oversized triangle covering the viewport; no vertex buffer needed.
@vertex
fn vs_main(@builtin(vertex_index) index: u32) -> VsOut {
    var positions = array<vec2<f32>, 3>(
        vec2<f32>(-1.0, -1.0),
        vec2<f32>(3.0, -1.0),
        vec2<f32>(-1.0, 3.0),
    );
    let p = positions[index];
    var out: VsOut;
    out.position = vec4<f32>(p, 0.0, 1.0);
    // Clip space is y-up, texture space is y-down.
    out.uv = vec2<f32>(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
    return out;
}

@fragment
fn fs_main(in: VsOut) -> @location(0) vec4<f32> {
    // Vanilla has no colour grade: the texture atlas is already authored in
    // the final palette and the lightmap only multiplies it. Keeping this a
    // straight copy is what stops grass/leaves from looking neon.
    return textureSample(scene_tex, scene_sampler, in.uv);
}
