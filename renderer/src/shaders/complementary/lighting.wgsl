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

// Independently authored WebGPU lighting primitives for the complementary
// renderer path. Not a line-by-line translation of proprietary GLSL.
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

fn ambient_hemisphere(normal: vec3<f32>, ao: f32) -> vec3<f32> {
    let up = max(normal.y * 0.5 + 0.5, 0.0);
    let sky = mix(vec3<f32>(0.42, 0.46, 0.52), frame.sky_color_shadow_bias.rgb, up);
    return sky * frame.sun_color_ambient.a * clamp(ao, 0.0, 1.0);
}

// Sky light, warm block light, direct sunlight, and self-illumination.
fn evaluate_lighting(
    albedo: vec3<f32>,
    normal: vec3<f32>,
    ambient_occlusion: f32,
    sky_light: f32,
    block_light: f32,
    emissive: vec3<f32>,
    world_position: vec3<f32>
) -> vec3<f32> {
    let n = normalize(normal);
    let sun_dir = normalize(frame.sun_direction_intensity.xyz);
    let ndotl = max(dot(n, sun_dir), 0.0);
    let visibility = directional_sun_shadow(world_position);
    let ambient = ambient_hemisphere(n, ambient_occlusion);
    let sky = frame.sky_color_shadow_bias.rgb * sky_light * 0.28;
    let block = vec3<f32>(1.0, 0.72, 0.48) * block_light * 0.85;
    let direct = frame.sun_color_ambient.rgb *
        ndotl * frame.sun_direction_intensity.w * visibility;
    // Emissive is added after albedo modulation so lava/glowstone stay self-lit.
    return albedo * (ambient + sky + block + direct) + emissive;
}

struct SsrResult {
    color: vec3<f32>,
    confidence: f32,
};

// View-space ray march; requires a scene depth prepass and matching matrices.
fn screen_space_reflection(
    view_position: vec3<f32>,
    view_normal: vec3<f32>,
    screen_uv: vec2<f32>
) -> SsrResult {
    var result: SsrResult;
    result.color = vec3<f32>(0.0);
    result.confidence = 0.0;
    if (screen_uv.x <= 0.0 || screen_uv.x >= 1.0 ||
        screen_uv.y <= 0.0 || screen_uv.y >= 1.0) {
        return result;
    }

    let incident = normalize(view_position);
    let ray_direction = normalize(reflect(incident, normalize(view_normal)));
    let max_distance = max(frame.ssr_parameters.x, 0.1);
    let step_length = max(frame.ssr_parameters.y, 0.025);
    let thickness = max(frame.ssr_parameters.z, 0.01);
    let steps = u32(clamp(ceil(max_distance / step_length), 1.0, 96.0));
    var distance_along_ray = step_length;

    for (var i = 0u; i < steps; i = i + 1u) {
        let ray_position = view_position + ray_direction * distance_along_ray;
        let clip = frame.projection * vec4<f32>(ray_position, 1.0);
        if (clip.w <= 0.0) { break; }
        let ndc = clip.xyz / clip.w;
        let uv = vec2<f32>(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
        if (uv.x <= 0.001 || uv.x >= 0.999 || uv.y <= 0.001 ||
            uv.y >= 0.999 || ndc.z <= 0.0 || ndc.z >= 1.0) {
            break;
        }
        let depth_size = textureDimensions(scene_depth);
        let pixel = clamp(vec2<i32>(uv * vec2<f32>(depth_size)),
                          vec2<i32>(0), vec2<i32>(depth_size) - vec2<i32>(1));
        let sampled_depth = textureLoad(scene_depth, pixel, 0).x;
        let scene_position = reconstruct_view_position(uv, sampled_depth);
        let separation = ray_position.z - scene_position.z;
        // View space looks down -Z; negative separation means ray passed behind
        // the visible surface. Thickness rejects most false positives.
        if (separation < 0.0 && abs(separation) <= thickness) {
            result.color = textureSampleLevel(scene_color, scene_sampler, uv, 0.0).rgb;
            let edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
            let edge_fade = smoothstep(0.0, 0.08, edge);
            let distance_fade = 1.0 - clamp(distance_along_ray / max_distance, 0.0, 1.0);
            result.confidence = edge_fade * distance_fade * frame.ssr_parameters.w;
            break;
        }
        distance_along_ray += step_length;
        if (distance_along_ray > max_distance) { break; }
    }
    return result;
}

fn composite_reflection(
    base_color: vec3<f32>,
    reflection: SsrResult,
    view_direction: vec3<f32>,
    normal: vec3<f32>,
    roughness: f32
) -> vec3<f32> {
    let ndotv = clamp(dot(normalize(normal), normalize(view_direction)), 0.0, 1.0);
    let fresnel = pow(1.0 - ndotv, 5.0);
    let smoothness = 1.0 - clamp(roughness, 0.0, 1.0);
    let weight = clamp(reflection.confidence * mix(0.04, 0.96, fresnel) *
                       smoothness, 0.0, 1.0);
    return mix(base_color, reflection.color, weight);
}
