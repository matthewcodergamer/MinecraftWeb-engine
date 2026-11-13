// Upstream source inspected from ComplementaryDevelopment/ComplementaryReimagined
// commit 7c784f3a3bfe5a997f1393ea036e4e2d9789f1be (release-labelled r5.9).
// The earlier r5.9.3 Actions ZIP was extracted only in an ephemeral runner;
// its source files were not retained, so byte-for-byte r5.9.3 equivalence has
// not yet been independently verified.

// Source-driven Complementary Reimagined formula ports.
// Reference files:
// shaders/program/gbuffers_water.glsl
// shaders/lib/materials/materialMethods/reflections.glsl
// shaders/lib/colors/lightAndAmbientColors.glsl
//
// The upstream source's large GetReflection() ray marcher depends on many
// pack macros, uniforms, samplers, fog/sky helpers and optional WSR paths.
// This module ports the exact water Fresnel weighting at its call site and
// the complete light/ambient color-mixing equations for the Overworld path.
// The caller supplies compile-time-configuration-dependent weather modifiers.

struct ComplementaryLightInputs {
    sky_color: vec3<f32>,
    vs_brightness: f32,
    noon_factor: f32,
    inv_noon_factor: f32,
    sun_visibility2: f32,
    rain_factor: f32,
    // Exact drlcRainM + drlcSnowM + drlcDryM term from the source config.
    biome_weather_light_offset: vec3<f32>,
    // Exact source rainShadowVisReduce value after its preprocessor branches.
    rain_shadow_visibility_reduce: f32,
    // true for COMPOSITE1's light-shaft variant; false for ground/cloud.
    composite1: u32,
    deferred1: u32,
};

struct ComplementaryLightColors {
    day_light_color: vec3<f32>,
    ambient_color: vec3<f32>,
    light_color: vec3<f32>,
};

fn complementary_light_and_ambient_colors(
    input: ComplementaryLightInputs
) -> ComplementaryLightColors {
    // lightAndAmbientColors.glsl: noon/sunset clear colors.
    let noon_clear_light_color = select(
        vec3<f32>(0.65, 0.55, 0.375) * 2.05,
        vec3<f32>(0.4, 0.75, 1.3),
        input.composite1 != 0u
    );
    let noon_clear_ambient_color = pow(input.sky_color, vec3<f32>(0.75)) * 0.85;
    let sunset_clear_light_color = select(
        pow(vec3<f32>(0.64, 0.45, 0.3),
            vec3<f32>(1.5 + input.inv_noon_factor)) * 5.0,
        pow(vec3<f32>(0.62, 0.39, 0.24),
            vec3<f32>(1.5 + input.inv_noon_factor)) * 6.8,
        input.composite1 != 0u
    );
    let sunset_clear_ambient_color =
        noon_clear_ambient_color * vec3<f32>(1.21, 0.92, 0.76) * 0.95;

    let noon_factor_dm = select(input.noon_factor,
                                input.noon_factor * input.noon_factor,
                                input.composite1 != 0u);
    let day_light_color = mix(sunset_clear_light_color,
                              noon_clear_light_color, noon_factor_dm);
    let day_ambient_color = mix(sunset_clear_ambient_color,
                                noon_clear_ambient_color, noon_factor_dm);

    // Night clear values and weather values are copied from the same
    // Overworld branch; preprocessor-selected biome/rain offsets are inputs.
    let night_clear_light_color = select(
        select(
            0.9 * vec3<f32>(0.15, 0.14, 0.20) *
                (0.4 + input.vs_brightness * 0.4),
            0.9 * vec3<f32>(0.11, 0.14, 0.20),
            input.deferred1 != 0u
        ),
        vec3<f32>(0.08, 0.12, 0.23),
        input.composite1 != 0u
    );
    let night_clear_ambient_color =
        0.9 * vec3<f32>(0.09, 0.12, 0.17) *
        (1.55 + input.vs_brightness * 0.77);
    let clear_light_color = mix(night_clear_light_color,
                                day_light_color, input.sun_visibility2);
    let clear_ambient_color = mix(night_clear_ambient_color,
                                  day_ambient_color, input.sun_visibility2);

    let day_rain_light_color =
        vec3<f32>(0.21, 0.16, 0.13) * 0.85 +
        input.noon_factor * vec3<f32>(0.0, 0.02, 0.06) +
        input.biome_weather_light_offset;
    let day_rain_ambient_color =
        vec3<f32>(0.2, 0.2, 0.25) * (1.8 + 0.5 * input.vs_brightness);
    let night_rain_light_color =
        vec3<f32>(0.03, 0.035, 0.05) *
        (0.5 + 0.5 * input.vs_brightness);
    let night_rain_ambient_color =
        vec3<f32>(0.16, 0.20, 0.3) *
        (0.75 + 0.6 * input.vs_brightness);

    let rain_light_color = mix(
        night_rain_light_color,
        day_rain_light_color * (1.0 - input.rain_shadow_visibility_reduce),
        input.sun_visibility2
    ) * 2.5;
    let rain_ambient_color = mix(
        night_rain_ambient_color,
        day_rain_ambient_color * (1.0 + input.rain_shadow_visibility_reduce),
        input.sun_visibility2
    );

    var output: ComplementaryLightColors;
    output.day_light_color = day_light_color;
    output.light_color = mix(clear_light_color, rain_light_color, input.rain_factor);
    output.ambient_color = mix(clear_ambient_color, rain_ambient_color, input.rain_factor);
    return output;
}

// Exact water Fresnel factor and final blend from gbuffers_water.glsl.
// normal_m and normalized_view_position must be in the same view space.
// reflect_mult is the material's reflectMult produced by material handling.
fn complementary_water_fresnel(
    normal_m: vec3<f32>,
    normalized_view_position: vec3<f32>
) -> f32 {
    let fresnel = clamp(1.0 + dot(normal_m, normalized_view_position), 0.0, 1.0);
    let fresnel_m = fresnel * fresnel * fresnel;
    return fresnel_m;
}


// GetReflection() Step 1 and ray-march initialization from reflections.glsl.
// The caller supplies the same view-space inputs and precomputed Fresnel.
fn complementary_reflection_direction(
    normalized_view_position: vec3<f32>,
    reflection_normal: vec3<f32>
) -> vec3<f32> {
    return normalize(reflect(normalized_view_position, reflection_normal));
}

fn complementary_reflection_ray_start(
    view_position: vec3<f32>,
    reflection_normal: vec3<f32>,
    view_position_length: f32,
    fresnel: f32
) -> vec3<f32> {
    return view_position + reflection_normal *
        (view_position_length * 0.025 * (1.0 - fresnel) + 0.05);
}

fn composite_water_reflection(
    base_color: vec3<f32>,
    reflection_color: vec3<f32>,
    normal_m: vec3<f32>,
    normalized_view_position: vec3<f32>,
    reflect_mult: f32
) -> vec3<f32> {
    let fresnel = complementary_water_fresnel(normal_m, normalized_view_position);
    let reflection_weight = (fresnel * 0.85 + 0.15) * reflect_mult;
    return mix(base_color, reflection_color, reflection_weight);
}

// Remaining renderer infrastructure scaffold: shadow-map sampling and SSR
// still require engine-specific pipeline integration and are not a port of
// Complementary's full GetReflection() implementation.
// See docs/COMPLEMENTARY_ASSETS.md.
//
// Bind group 1 is separate from the vanilla block pass in renderer/src/shader.wgsl.

struct ComplementaryFrame {
    view: mat4x4<f32>,
    projection: mat4x4<f32>,
    inverse_projection: mat4x4<f32>,
    world_to_shadow: mat4x4<f32>,
    // xyz = view-space direction FROM surface TOWARD sun; w = sun intensity.
    sun_direction_intensity: vec4<f32>,
    // rgb = sun radiance; a = ambient intensity.
    sun_color_ambient: vec4<f32>,
    // rgb = sky tint; a = shadow depth bias.
    sky_color_shadow_bias: vec4<f32>,
    // x = SSR max distance, y = step length, z = thickness, w = SSR strength.
    ssr_parameters: vec4<f32>,
    // xy = viewport dimensions.
    viewport: vec4<f32>,
};

@group(1) @binding(0) var<uniform> frame: ComplementaryFrame;
@group(1) @binding(1) var scene_depth: texture_2d<f32>;
@group(1) @binding(2) var scene_color: texture_2d<f32>;
@group(1) @binding(3) var scene_sampler: sampler;
@group(1) @binding(4) var sun_shadow_map: texture_depth_2d;
@group(1) @binding(5) var shadow_sampler: sampler_comparison;

fn reconstruct_view_position(uv: vec2<f32>, depth: f32) -> vec3<f32> {
    let ndc = vec4<f32>(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, depth, 1.0);
    let h = frame.inverse_projection * ndc;
    return h.xyz / max(abs(h.w), 0.000001) * sign(h.w);
}

// Directional sun shadow with 3x3 percentage-closer filtering.
// world_to_shadow maps world coordinates into WebGPU clip/depth space.
fn directional_sun_shadow(world_position: vec3<f32>) -> f32 {
    let h = frame.world_to_shadow * vec4<f32>(world_position, 1.0);
    let ndc = h.xyz / h.w;
    let uv = vec2<f32>(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0 ||
        ndc.z < 0.0 || ndc.z > 1.0) {
        return 1.0;
    }
    let texel = 1.0 / vec2<f32>(textureDimensions(sun_shadow_map));
    let compare_depth = ndc.z - frame.sky_color_shadow_bias.a;
    var visibility = 0.0;
    for (var y = -1; y <= 1; y = y + 1) {
        for (var x = -1; x <= 1; x = x + 1) {
            let offset = vec2<f32>(f32(x), f32(y)) * texel;
            visibility += textureSampleCompare(
                sun_shadow_map, shadow_sampler,
                clamp(uv + offset, vec2<f32>(0.001), vec2<f32>(0.999)),
                compare_depth
            );
        }
    }
    return visibility / 9.0;
}

// Source-driven evaluate_lighting entry point. The output preserves the
// source's separate lightColor and ambientColor terms for downstream material
// lighting rather than inventing a new Lambert/block-light combination.
fn evaluate_lighting(input: ComplementaryLightInputs) -> ComplementaryLightColors {
    return complementary_light_and_ambient_colors(input);
}

struct SsrResult {
    color: vec3<f32>,
    confidence: f32,
    hit_uv: vec2<f32>,
    hit_distance: f32,
};

// Port of the core screen-space ray-march/refinement and edge-confidence path
// in Complementary reflections.glsl::GetReflection(). Engine-specific
// WSR, DH/Voxy depth overrides, DoFog(), mirrored fallback and reprojection
// are intentionally outside this Overworld screen-space variant.
//
// Matrices must be the WebGPU-adjusted equivalents of gbufferProjection and
// gbufferProjectionInverse. Depth is WebGPU [0,1]; scene_depth is point-loaded
// to avoid implicit-derivative sampling in the non-uniform march loop.
fn complementary_get_reflection_ssr(
    view_position: vec3<f32>,
    normal_m: vec3<f32>,
    normalized_view_position: vec3<f32>,
    player_view_length: f32,
    screen_uv: vec2<f32>,
    fresnel: f32,
    dither: f32,
    smoothness: f32,
    z0: f32,
    water_style: u32
) -> SsrResult {
    var result: SsrResult;
    result.color = vec3<f32>(0.0);
    result.confidence = 0.0;
    result.hit_uv = screen_uv;
    result.hit_distance = frame.ssr_parameters.x;

    // GetReflection Step 1: prepare reflected direction and start point.
    let r_edge = vec2<f32>(0.525, 0.525);
    var normal_mr = normalize(normal_m);
    let reflected_direction = normalize(reflect(normalized_view_position, normal_mr));
    let start = view_position + normal_mr *
        (player_view_length * 0.025 * (1.0 - fresnel) + 0.05);

    // WATER_STYLE >= 2 uses a second normal blend before calculating the ray.
    // The caller supplies the already material-adjusted normal for that mode.
    var ray_direction = reflected_direction * 0.5;
    if (water_style >= 2u) {
        ray_direction = normalize(reflect(normalized_view_position, normal_mr)) * 0.5;
    }
    let base_step = ray_direction;
    var ray_position = view_position + ray_direction;
    var step_vector = ray_direction;

    // Standard internal screen-space mode uses 38 samples and 10 refinements.
    // The legacy -1 mode uses 30/6; select it by setting ssr_parameters.w < 0.
    let legacy_mode = frame.ssr_parameters.w < 0.0;
    let sample_count = select(38u, 30u, legacy_mode);
    let refinement_count = select(10u, 6u, legacy_mode);
    let edge = select(vec2<f32>(0.525, 0.525), vec2<f32>(0.6, 0.55), legacy_mode);

    var refinement = 0u;
    var distance_to_hit = 0.0;
    var error_distance = 9999999.0;
    var hit_view_position = vec3<f32>(0.0);
    var hit_uvz = vec3<f32>(0.0, 0.0, 1.0);
    var sampled_depth = 1.0;

    for (var i = 0u; i < 38u; i = i + 1u) {
        if (i >= sample_count) { break; }
        let clip = frame.projection * vec4<f32>(ray_position, 1.0);
        if (clip.w <= 0.0) { break; }
        let ndc = clip.xyz / clip.w;
        let uv = vec2<f32>(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
        if (abs(uv.x - 0.5) > edge.x || abs(uv.y - 0.5) > edge.y) {
            break;
        }

        let dimensions = textureDimensions(scene_depth);
        let pixel = clamp(vec2<i32>(uv * vec2<f32>(dimensions)),
                          vec2<i32>(0), vec2<i32>(dimensions) - vec2<i32>(1));
        sampled_depth = textureLoad(scene_depth, pixel, 0).x;
        hit_view_position = reconstruct_view_position(uv, sampled_depth);
        distance_to_hit = length(start - hit_view_position);
        error_distance = length(ray_position - hit_view_position);

        if (error_distance * 0.33333 < length(ray_direction)) {
            refinement += 1u;
            if (refinement >= refinement_count) {
                hit_uvz = vec3<f32>(uv, sampled_depth);
                break;
            }
            step_vector -= ray_direction;
            ray_direction *= 0.1;
        }

        ray_direction *= 2.0;
        step_vector += ray_direction * (0.95 + 0.1 * dither);
        ray_position = start + step_vector;
        hit_uvz = vec3<f32>(uv, sampled_depth);
    }

    let hit_view_length = length(hit_view_position);
    let depth_is_geometry = hit_uvz.z < 1.0;
    // The source uses a geometry-facing test in gbuffers; in this reusable
    // function we conservatively require a valid depth hit. Caller may add
    // the original ScreenToView()/geoNormal test for gbuffers_water.
    let possible_reflection = depth_is_geometry;

    if (possible_reflection &&
        error_distance * (1.0 - fresnel) < 1.0 + hit_view_length * 0.2) {
        let abs_position = abs(hit_uvz.xy - vec2<f32>(0.5));
        let cdist = abs_position / edge;
        let border = clamp(1.0 - pow(max(cdist.x, cdist.y), 50.0), 0.0, 1.0);
        result.confidence = border;

        if (result.confidence > 0.001) {
            var sample_uv = hit_uvz.xy;
            let edge_factor0 = cdist * cdist;
            let edge_factor1 = edge_factor0 * edge_factor0;
            let edge_factor = edge_factor1 * edge_factor1;
            if (legacy_mode) {
                sample_uv.y += (dither - 0.5) *
                    (0.05 * (edge_factor.x + edge_factor.y));
            }

            // Explicit LOD is required here: this sample occurs after
            // non-uniform ray-march control flow. Smoothness/roughness LOD
            // follows the source's exponential distance response.
            let smoothness_dm = smoothness * smoothness;
            let lod_factor = 1.0 - exp(-0.125 * (1.0 - smoothness_dm) * distance_to_hit);
            var lod = log2(max(frame.viewport.y / 8.0 *
                (1.0 - smoothness_dm) * lod_factor, 0.0001)) * 0.45;
            if (z0 <= 0.56) { lod *= 2.22; }
            lod = max(lod - 1.0, 0.0);
            result.color = textureSampleLevel(scene_color, scene_sampler, sample_uv, lod).rgb;

            var edge_fade = edge_factor;
            edge_fade.x *= edge_fade.x;
            edge_fade = vec2<f32>(1.0) - edge_fade;
            let luminance = dot(result.color, vec3<f32>(0.2126, 0.7152, 0.0722));
            let ref_factor = pow(max(edge_fade.x * edge_fade.y, 0.0),
                                 2.0 + 3.0 * luminance);
            result.confidence *= ref_factor;
            let position_difference = hit_view_length - player_view_length;
            result.confidence *= clamp(position_difference + 3.0, 0.0, 1.0);
            result.hit_uv = sample_uv;
            result.hit_distance = distance_to_hit;
        }
    }

    return result;
}
