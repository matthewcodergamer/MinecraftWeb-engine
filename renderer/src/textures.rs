//! The vanilla block texture array.
//!
//! PNG bytes for each layer arrive from the page (which reads them out of the
//! user's own client jar) and are decoded here. Layers are 16x16, RGBA8, with a
//! full mip chain; an array (not an atlas) is used so a greedily merged quad can
//! repeat its tile with the sampler instead of bleeding into a neighbour.

use std::cell::RefCell;

use wasm_bindgen::prelude::*;

pub const TILE: u32 = 16;
const MIP_LEVELS: u32 = 5; // 16, 8, 4, 2, 1

/// Layer order, mirrored by `material.rs`.
///
/// These are the client jar's own 16x16 PNGs. The first sixteen layers keep the
/// original M2b order so existing demo IDs do not move; the rest cover the blocks
/// a real 26.3 chunk palette is likely to contain. 85 layers is well below the
/// WebGPU `maxTextureArrayLayers` floor of 256.
pub const TEXTURE_NAMES: [&str; 99] = [
    "assets/minecraft/textures/block/stone.png",
    "assets/minecraft/textures/block/dirt.png",
    "assets/minecraft/textures/block/grass_block_top.png",
    "assets/minecraft/textures/block/grass_block_side.png",
    "assets/minecraft/textures/block/sand.png",
    "assets/minecraft/textures/block/oak_log.png",
    "assets/minecraft/textures/block/oak_log_top.png",
    "assets/minecraft/textures/block/oak_leaves.png",
    "assets/minecraft/textures/block/oak_planks.png",
    "assets/minecraft/textures/block/cobblestone.png",
    "assets/minecraft/textures/block/bedrock.png",
    "assets/minecraft/textures/block/coal_ore.png",
    "assets/minecraft/textures/block/iron_ore.png",
    "assets/minecraft/textures/block/gravel.png",
    "assets/minecraft/textures/block/snow.png",
    "assets/minecraft/textures/block/glass.png",
    "assets/minecraft/textures/block/gold_ore.png",
    "assets/minecraft/textures/block/diamond_ore.png",
    "assets/minecraft/textures/block/redstone_ore.png",
    "assets/minecraft/textures/block/water_still.png",
    "assets/minecraft/textures/block/lava_still.png",
    "assets/minecraft/textures/block/sandstone.png",
    "assets/minecraft/textures/block/sandstone_top.png",
    "assets/minecraft/textures/block/andesite.png",
    "assets/minecraft/textures/block/diorite.png",
    "assets/minecraft/textures/block/granite.png",
    "assets/minecraft/textures/block/deepslate.png",
    "assets/minecraft/textures/block/deepslate_top.png",
    "assets/minecraft/textures/block/tuff.png",
    "assets/minecraft/textures/block/calcite.png",
    "assets/minecraft/textures/block/obsidian.png",
    "assets/minecraft/textures/block/netherrack.png",
    "assets/minecraft/textures/block/soul_sand.png",
    "assets/minecraft/textures/block/glowstone.png",
    "assets/minecraft/textures/block/ice.png",
    "assets/minecraft/textures/block/packed_ice.png",
    "assets/minecraft/textures/block/blue_ice.png",
    "assets/minecraft/textures/block/clay.png",
    "assets/minecraft/textures/block/terracotta.png",
    "assets/minecraft/textures/block/bricks.png",
    "assets/minecraft/textures/block/stone_bricks.png",
    "assets/minecraft/textures/block/mossy_cobblestone.png",
    "assets/minecraft/textures/block/short_grass.png",
    "assets/minecraft/textures/block/poppy.png",
    "assets/minecraft/textures/block/dandelion.png",
    "assets/minecraft/textures/block/oak_sapling.png",
    "assets/minecraft/textures/block/grass_block_snow.png",
    "assets/minecraft/textures/block/powder_snow.png",
    "assets/minecraft/textures/block/mud.png",
    "assets/minecraft/textures/block/packed_mud.png",
    "assets/minecraft/textures/block/mud_bricks.png",
    "assets/minecraft/textures/block/copper_ore.png",
    "assets/minecraft/textures/block/raw_iron_block.png",
    "assets/minecraft/textures/block/raw_copper_block.png",
    "assets/minecraft/textures/block/raw_gold_block.png",
    "assets/minecraft/textures/block/coal_block.png",
    "assets/minecraft/textures/block/iron_block.png",
    "assets/minecraft/textures/block/gold_block.png",
    "assets/minecraft/textures/block/diamond_block.png",
    "assets/minecraft/textures/block/emerald_block.png",
    "assets/minecraft/textures/block/lapis_block.png",
    "assets/minecraft/textures/block/redstone_block.png",
    "assets/minecraft/textures/block/netherite_block.png",
    "assets/minecraft/textures/block/amethyst_block.png",
    "assets/minecraft/textures/block/budding_amethyst.png",
    "assets/minecraft/textures/block/copper_block.png",
    "assets/minecraft/textures/block/exposed_copper.png",
    "assets/minecraft/textures/block/weathered_copper.png",
    "assets/minecraft/textures/block/oxidized_copper.png",
    "assets/minecraft/textures/block/cut_copper.png",
    "assets/minecraft/textures/block/moss_block.png",
    "assets/minecraft/textures/block/red_sand.png",
    "assets/minecraft/textures/block/red_sandstone.png",
    "assets/minecraft/textures/block/smooth_stone.png",
    "assets/minecraft/textures/block/blackstone.png",
    "assets/minecraft/textures/block/end_stone.png",
    "assets/minecraft/textures/block/purpur_block.png",
    "assets/minecraft/textures/block/ancient_debris_side.png",
    "assets/minecraft/textures/block/ancient_debris_top.png",
    "assets/minecraft/textures/block/basalt_side.png",
    "assets/minecraft/textures/block/basalt_top.png",
    "assets/minecraft/textures/block/podzol_top.png",
    "assets/minecraft/textures/block/podzol_side.png",
    "assets/minecraft/textures/block/mycelium_top.png",
    "assets/minecraft/textures/block/mycelium_side.png",
    "assets/minecraft/textures/block/lapis_ore.png",
    "assets/minecraft/textures/block/emerald_ore.png",
    "assets/minecraft/textures/block/deepslate_coal_ore.png",
    "assets/minecraft/textures/block/deepslate_iron_ore.png",
    "assets/minecraft/textures/block/deepslate_gold_ore.png",
    "assets/minecraft/textures/block/deepslate_redstone_ore.png",
    "assets/minecraft/textures/block/deepslate_lapis_ore.png",
    "assets/minecraft/textures/block/deepslate_diamond_ore.png",
    "assets/minecraft/textures/block/deepslate_emerald_ore.png",
    "assets/minecraft/textures/block/deepslate_copper_ore.png",
    "assets/minecraft/textures/block/dirt_path_top.png",
    "assets/minecraft/textures/block/dirt_path_side.png",
    "assets/minecraft/textures/block/farmland.png",
    "assets/minecraft/textures/block/farmland_moist.png",
];

/// Vanilla biome tints that are baked into greyscale block textures.
/// Plains grass `#91BD59`, plains foliage `#59AE30`.
const TINT_GRASS: [u8; 3] = [0x91, 0xBD, 0x59];
const TINT_LEAVES: [u8; 3] = [0x59, 0xAE, 0x30];

thread_local! {
    static PNG: RefCell<Vec<Option<Vec<u8>>>> = const { RefCell::new(Vec::new()) };
}

/// Number of layers the page must upload.
#[wasm_bindgen]
pub fn texture_count() -> u32 {
    TEXTURE_NAMES.len() as u32
}

/// Asset path of a layer, so the page can find it in the resource archive.
#[wasm_bindgen]
pub fn texture_name(layer: u32) -> String {
    TEXTURE_NAMES
        .get(layer as usize)
        .copied()
        .unwrap_or("")
        .to_string()
}

/// Resolve a vanilla asset path (`assets/minecraft/textures/block/dirt.png`) to
/// its array layer. Returns -1 when the Body does not carry that texture.
/// Java/JS use this when they send a material table for real block-state IDs.
#[wasm_bindgen]
pub fn texture_layer(path: &str) -> i32 {
    layer_for_path(path).map(|layer| layer as i32).unwrap_or(-1)
}

pub fn layer_for_path(path: &str) -> Option<u32> {
    // Accept both the canonical asset path and the short `block/dirt.png` form.
    TEXTURE_NAMES
        .iter()
        .position(|name| *name == path || name.ends_with(path))
        .map(|index| index as u32)
}

/// Bridge for the page: hand over one PNG from the client jar.
#[wasm_bindgen]
pub fn upload_texture(layer: u32, png: &[u8]) {
    if layer as usize >= TEXTURE_NAMES.len() {
        return;
    }
    PNG.with(|cell| {
        let mut pngs = cell.borrow_mut();
        if pngs.len() <= layer as usize {
            pngs.resize(layer as usize + 1, None);
        }
        pngs[layer as usize] = Some(png.to_vec());
    });
}

/// Decoded, mip-mapped texture + view ready for the block pipeline.
pub struct BlockTextures {
    #[allow(dead_code)]
    pub texture: wgpu::Texture,
    pub view: wgpu::TextureView,
    pub sampler: wgpu::Sampler,
}

pub fn build(device: &wgpu::Device, queue: &wgpu::Queue) -> BlockTextures {
    let layers = TEXTURE_NAMES.len() as u32;
    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("mc-body/blocks"),
        size: wgpu::Extent3d {
            width: TILE,
            height: TILE,
            depth_or_array_layers: layers,
        },
        mip_level_count: MIP_LEVELS,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        // Raw sRGB bytes, *not* `Rgba8UnormSrgb`. WebGPU canvas swap chains
        // cannot be sRGB (`bgra8unorm` only), so sampling through an sRGB
        // texture would linearise the texels and we would write them back as
        // if they were sRGB — halving every channel. Vanilla multiplies sRGB
        // texels by the face shade directly, so keeping the texture raw and
        // doing the maths in sRGB space reproduces the Java look exactly.
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
        view_formats: &[],
    });

    for layer in 0..layers {
        let rgba = PNG.with(|cell| {
            cell.borrow()
                .get(layer as usize)
                .and_then(|png| png.as_ref())
                .map(|png| decode(png, layer))
        });
        let rgba = rgba.unwrap_or_else(missing_tile);
        upload_mips(queue, &texture, layer, &rgba);
    }

    let view = texture.create_view(&wgpu::TextureViewDescriptor {
        label: Some("mc-body/blocks.view"),
        dimension: Some(wgpu::TextureViewDimension::D2Array),
        ..Default::default()
    });

    // Nearest magnification keeps the 16x16 pixel art crisp exactly like the
    // vanilla default (`mipmapLevels=4`, nearest mag, linear mip blend).
    let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
        label: Some("mc-body/blocks.sampler"),
        address_mode_u: wgpu::AddressMode::Repeat,
        address_mode_v: wgpu::AddressMode::Repeat,
        address_mode_w: wgpu::AddressMode::ClampToEdge,
        mag_filter: wgpu::FilterMode::Nearest,
        min_filter: wgpu::FilterMode::Nearest,
        mipmap_filter: wgpu::MipmapFilterMode::Linear,
        ..Default::default()
    });

    BlockTextures {
        texture,
        view,
        sampler,
    }
}

/// Decode a PNG into a 16x16 RGBA tile, taking the first frame of animations.
fn decode(png: &[u8], layer: u32) -> Vec<u8> {
    let mut decoder = png::Decoder::new(std::io::Cursor::new(png));
    // EXPAND turns palette images into RGB(A) and applies tRNS transparency.
    decoder.set_transformations(png::Transformations::EXPAND);
    let mut reader = match decoder.read_info() {
        Ok(reader) => reader,
        Err(_) => return missing_tile(),
    };
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let info = match reader.next_frame(&mut buf) {
        Ok(info) => info,
        Err(_) => return missing_tile(),
    };

    let width = info.width.max(1);
    let height = info.height.max(1);
    let channels = match info.color_type {
        png::ColorType::Grayscale => 1usize,
        png::ColorType::GrayscaleAlpha => 2,
        png::ColorType::Rgb => 3,
        png::ColorType::Rgba => 4,
        png::ColorType::Indexed => 1,
    };

    // Animated textures (water/fire) are stacked vertically; the first 16x16
    // frame is the still image.
    let frame_height = height.min(TILE);
    let tint = match layer {
        2 | 42 => Some(TINT_GRASS), // grass_block_top, short_grass (greyscale)
        7 => Some(TINT_LEAVES),     // oak_leaves (greyscale)
        crate::material::L_WATER => Some([0x3F, 0x76, 0xE4]), // vanilla water tint
        _ => None,
    };

    let mut out = vec![0u8; (TILE * TILE * 4) as usize];
    for y in 0..TILE {
        for x in 0..TILE {
            let sx = (x * width / TILE).min(width - 1) as usize;
            let sy = (y * frame_height / TILE).min(frame_height - 1) as usize;
            let i = (sy * width as usize + sx) * channels;
            let (mut r, mut g, mut b, a) = match channels {
                1 => (buf[i], buf[i], buf[i], 255),
                2 => (buf[i], buf[i], buf[i], buf[i + 1]),
                3 => (buf[i], buf[i + 1], buf[i + 2], 255),
                _ => (buf[i], buf[i + 1], buf[i + 2], buf[i + 3]),
            };
            if let Some(t) = tint {
                r = (r as u32 * t[0] as u32 / 255) as u8;
                g = (g as u32 * t[1] as u32 / 255) as u8;
                b = (b as u32 * t[2] as u32 / 255) as u8;
            }
            let o = ((y * TILE + x) * 4) as usize;
            out[o] = r;
            out[o + 1] = g;
            out[o + 2] = b;
            out[o + 3] = a;
        }
    }
    // The vanilla leaf PNG is a cutout with 33% fully transparent pixels. On a
    // browser rasteriser that reads as a confetti canopy you can see the sky
    // through. Close the 1-pixel gaps with the surrounding leaf colour (the
    // "fast leaves" density players actually recognise) while keeping the rest
    // of the cutout shape.
    if layer == crate::material::L_LEAVES {
        densify_leaf(&mut out);
    }
    out
}

fn densify_leaf(tile: &mut [u8]) {
    const N: usize = TILE as usize;
    let source = tile.to_vec();
    for y in 0..N {
        for x in 0..N {
            let o = (y * N + x) * 4;
            if source[o + 3] >= 128 {
                continue;
            }
            let mut r = 0u32;
            let mut g = 0u32;
            let mut b = 0u32;
            let mut n = 0u32;
            for dy in [-1i32, 0, 1] {
                for dx in [-1i32, 0, 1] {
                    if dx == 0 && dy == 0 {
                        continue;
                    }
                    let nx = (x as i32 + dx).rem_euclid(N as i32) as usize;
                    let ny = (y as i32 + dy).rem_euclid(N as i32) as usize;
                    let i = (ny * N + nx) * 4;
                    if source[i + 3] < 128 {
                        continue;
                    }
                    r += u32::from(source[i]);
                    g += u32::from(source[i + 1]);
                    b += u32::from(source[i + 2]);
                    n += 1;
                }
            }
            if n > 0 {
                // A touch darker than the surrounding leaves so the filled cell
                // reads as depth inside the canopy, not a flat green square.
                tile[o] = ((r / n) * 88 / 100) as u8;
                tile[o + 1] = ((g / n) * 88 / 100) as u8;
                tile[o + 2] = ((b / n) * 88 / 100) as u8;
                tile[o + 3] = 255;
            }
        }
    }
}

fn missing_tile() -> Vec<u8> {
    // Magenta/black checker, the classic missing-texture marker.
    let mut out = vec![0u8; (TILE * TILE * 4) as usize];
    for y in 0..TILE {
        for x in 0..TILE {
            let on = ((x / 8) + (y / 8)) % 2 == 0;
            let o = ((y * TILE + x) * 4) as usize;
            let (r, g, b) = if on { (255, 0, 255) } else { (0, 0, 0) };
            out[o] = r;
            out[o + 1] = g;
            out[o + 2] = b;
            out[o + 3] = 255;
        }
    }
    out
}

/// Upload level 0 and a box-filtered mip chain for one array layer.
fn upload_mips(queue: &wgpu::Queue, texture: &wgpu::Texture, layer: u32, level0: &[u8]) {
    let mut size = TILE;
    let mut data = level0.to_vec();
    for level in 0..MIP_LEVELS {
        queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture,
                mip_level: level,
                origin: wgpu::Origin3d {
                    x: 0,
                    y: 0,
                    z: layer,
                },
                aspect: wgpu::TextureAspect::All,
            },
            &data,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(size * 4),
                rows_per_image: Some(size),
            },
            wgpu::Extent3d {
                width: size,
                height: size,
                depth_or_array_layers: 1,
            },
        );
        if level + 1 == MIP_LEVELS {
            break;
        }
        data = downsample(&data, size);
        size /= 2;
    }
}

fn downsample(src: &[u8], size: u32) -> Vec<u8> {
    let half = (size / 2).max(1);
    let mut out = vec![0u8; (half * half * 4) as usize];
    for y in 0..half {
        for x in 0..half {
            let mut acc = [0u32; 4];
            for dy in 0..2u32 {
                for dx in 0..2u32 {
                    let si = (((y * 2 + dy) * size + (x * 2 + dx)) * 4) as usize;
                    for c in 0..4 {
                        acc[c] += src[si + c] as u32;
                    }
                }
            }
            let o = ((y * half + x) * 4) as usize;
            for c in 0..4 {
                out[o + c] = (acc[c] / 4) as u8;
            }
        }
    }
    out
}
