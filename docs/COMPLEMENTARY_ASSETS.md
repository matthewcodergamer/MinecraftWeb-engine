# Complementary-style PBR asset integration

This guide describes a renderer-side convention for optional Minecraft PBR
textures. It does not assume every Complementary release uses identical
filenames, channel packing, or material semantics.

## License and source handling

Complementary shader-pack files are third-party assets governed by the license
included with each release. The upstream pack license restricts modification
and redistribution. The fetch workflow therefore downloads and extracts GLSL
only into its temporary GitHub Actions workspace for inspection; it does not
commit the pack, publish it as an artifact, or bundle it with this repository.
Review the exact release license before retaining, redistributing, or adapting
any upstream file.

The WGSL in renderer/src/shaders/complementary/lighting.wgsl is an
independently authored implementation of common rendering techniques. It is
not a verified line-by-line translation of composite.fsh or water.fsh.

## Texture discovery

For resource packs following the common suffix convention:

- <base>_n.png: tangent-space normal map.
- <base>_s.png: specular/material map.

Treat these as optional siblings of the base color texture. Resolve them
through the same resource-pack namespace and override order as the albedo
texture. Cache by canonical resource identifier and invalidate all maps
together when a pack changes.

The suffix alone does not define channel meaning. Minecraft shader ecosystems
use multiple PBR specifications and pack versions. Read the pack's format
documentation/metadata and inspect representative textures before decoding.
Roughness, smoothness, metalness, porosity, emissive, and subsurface values may
be packed differently.

## GPU upload and sampling

1. Load base color, normal, and specular maps independently.
2. Preserve source dimensions; bind a neutral fallback when a map is absent.
3. Upload normal maps as linear data, never sRGB. Decode RGB from [0, 1] to
   tangent-space [-1, 1], then normalize after interpolation.
4. Upload material/specular maps as linear data. Do not apply an sRGB transfer
   function to packed numeric channels.
5. Use matching UV transforms, mip policy, filtering, and anisotropy for all
   maps belonging to one material.
6. Build or obtain a tangent basis (TBN). Transform the decoded tangent-space
   normal into world or view space before lighting.
7. Decode specular channels only after identifying the format. Convert
   roughness/smoothness to the renderer's chosen convention exactly once.
8. Use explicit neutral defaults: flat normal (0.5, 0.5, 1.0), dielectric
   reflectance, and non-emissive material.

## Suggested Rust material representation

Keep optional PBR maps separate from the existing vanilla block texture array:

- albedo_view
- normal_view: Option<TextureView>
- material_view: Option<TextureView>
- material_format: enum identifying the supported channel convention
- normal_scale
- roughness_scale

Never silently interpret an unknown _s layout. Log the unsupported format
and use a neutral fallback. Add a small fixture pack with known channel values
and unit tests for decoding before enabling PBR maps globally.

## Pipeline integration

The existing renderer/src/shader.wgsl remains unchanged. The new lighting
module uses bind group 1 so it can be introduced as a separate pipeline or
composed into a later shader after the Rust bind-group layout is defined.
Shadows and SSR require depth/color prepasses and camera matrices; they cannot
produce correct results from the current block fragment inputs alone.
