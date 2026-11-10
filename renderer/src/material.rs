//! Block-state -> texture layer + vanilla face shading.
//!
//! The Body's only interpretation of a block ID is this table. It starts with
//! the synthetic demo palette so M2b keeps working, then the Java Brain (or the
//! page's palette bridge) registers real `Block.getId(state)` values by registry
//! name. Meshing consults the table; it never calls back into Java.
//!
//! Vanilla resolves block textures through models and block-state properties.
//! The Body deliberately keeps the first pass simpler: one top/bottom/side
//! texture per block name. That is exact for stone/dirt/ores/logs/grass and a
//! close match for the rest; the model loader remains a later milestone.

use std::cell::RefCell;
use std::collections::HashMap;

use wasm_bindgen::prelude::*;

/// Face order used by the mesher: `-x, +x, -y, +y, -z, +z`.
pub const FACE_NEG_X: u8 = 0;
pub const FACE_POS_X: u8 = 1;
pub const FACE_NEG_Y: u8 = 2;
pub const FACE_POS_Y: u8 = 3;
pub const FACE_NEG_Z: u8 = 4;
pub const FACE_POS_Z: u8 = 5;

// Texture-array layers, in the order `textures::TEXTURE_NAMES` declares them.
pub const L_STONE: u32 = 0;
pub const L_DIRT: u32 = 1;
pub const L_GRASS_TOP: u32 = 2;
pub const L_GRASS_SIDE: u32 = 3;
pub const L_SAND: u32 = 4;
pub const L_LOG: u32 = 5;
pub const L_LOG_TOP: u32 = 6;
pub const L_LEAVES: u32 = 7;
pub const L_PLANKS: u32 = 8;
pub const L_COBBLE: u32 = 9;
pub const L_BEDROCK: u32 = 10;
pub const L_COAL_ORE: u32 = 11;
pub const L_IRON_ORE: u32 = 12;
pub const L_GRAVEL: u32 = 13;
pub const L_SNOW: u32 = 14;
pub const L_GLASS: u32 = 15;
pub const L_GOLD_ORE: u32 = 16;
pub const L_DIAMOND_ORE: u32 = 17;
pub const L_REDSTONE_ORE: u32 = 18;
pub const L_WATER: u32 = 19;
pub const L_LAVA: u32 = 20;
pub const L_SANDSTONE: u32 = 21;
pub const L_SANDSTONE_TOP: u32 = 22;
pub const L_ANDESITE: u32 = 23;
pub const L_DIORITE: u32 = 24;
pub const L_GRANITE: u32 = 25;
pub const L_DEEPSLATE: u32 = 26;
pub const L_DEEPSLATE_TOP: u32 = 27;
pub const L_TUFF: u32 = 28;
pub const L_CALCITE: u32 = 29;
pub const L_OBSIDIAN: u32 = 30;
pub const L_NETHERRACK: u32 = 31;
pub const L_SOUL_SAND: u32 = 32;
pub const L_GLOWSTONE: u32 = 33;
pub const L_ICE: u32 = 34;
pub const L_PACKED_ICE: u32 = 35;
pub const L_BLUE_ICE: u32 = 36;
pub const L_CLAY: u32 = 37;
pub const L_TERRACOTTA: u32 = 38;
pub const L_BRICKS: u32 = 39;
pub const L_STONE_BRICKS: u32 = 40;
pub const L_MOSSY_COBBLE: u32 = 41;
pub const L_SHORT_GRASS: u32 = 42;
pub const L_POPPY: u32 = 43;
pub const L_DANDELION: u32 = 44;
pub const L_SAPLING: u32 = 45;
pub const L_GRASS_SNOW: u32 = 46;
pub const L_POWDER_SNOW: u32 = 47;
pub const L_MUD: u32 = 48;
pub const L_PACKED_MUD: u32 = 49;
pub const L_MUD_BRICKS: u32 = 50;
pub const L_COPPER_ORE: u32 = 51;
pub const L_RAW_IRON: u32 = 52;
pub const L_RAW_COPPER: u32 = 53;
pub const L_RAW_GOLD: u32 = 54;
pub const L_COAL_BLOCK: u32 = 55;
pub const L_IRON_BLOCK: u32 = 56;
pub const L_GOLD_BLOCK: u32 = 57;
pub const L_DIAMOND_BLOCK: u32 = 58;
pub const L_EMERALD_BLOCK: u32 = 59;
pub const L_LAPIS_BLOCK: u32 = 60;
pub const L_REDSTONE_BLOCK: u32 = 61;
pub const L_NETHERITE_BLOCK: u32 = 62;
pub const L_AMETHYST: u32 = 63;
pub const L_BUDDING_AMETHYST: u32 = 64;
pub const L_COPPER_BLOCK: u32 = 65;
pub const L_EXPOSED_COPPER: u32 = 66;
pub const L_WEATHERED_COPPER: u32 = 67;
pub const L_OXIDIZED_COPPER: u32 = 68;
pub const L_CUT_COPPER: u32 = 69;
pub const L_MOSS_BLOCK: u32 = 70;
pub const L_RED_SAND: u32 = 71;
pub const L_RED_SANDSTONE: u32 = 72;
pub const L_SMOOTH_STONE: u32 = 73;
pub const L_BLACKSTONE: u32 = 74;
pub const L_END_STONE: u32 = 75;
pub const L_PURPUR: u32 = 76;
pub const L_ANCIENT_DEBRIS_SIDE: u32 = 77;
pub const L_ANCIENT_DEBRIS_TOP: u32 = 78;
pub const L_BASALT_SIDE: u32 = 79;
pub const L_BASALT_TOP: u32 = 80;
pub const L_PODZOL_TOP: u32 = 81;
pub const L_PODZOL_SIDE: u32 = 82;
pub const L_MYCELIUM_TOP: u32 = 83;
pub const L_MYCELIUM_SIDE: u32 = 84;
pub const L_LAPIS_ORE: u32 = 85;
pub const L_EMERALD_ORE: u32 = 86;
pub const L_DEEPSLATE_COAL_ORE: u32 = 87;
pub const L_DEEPSLATE_IRON_ORE: u32 = 88;
pub const L_DEEPSLATE_GOLD_ORE: u32 = 89;
pub const L_DEEPSLATE_REDSTONE_ORE: u32 = 90;
pub const L_DEEPSLATE_LAPIS_ORE: u32 = 91;
pub const L_DEEPSLATE_DIAMOND_ORE: u32 = 92;
pub const L_DEEPSLATE_EMERALD_ORE: u32 = 93;
pub const L_DEEPSLATE_COPPER_ORE: u32 = 94;
pub const L_DIRT_PATH_TOP: u32 = 95;
pub const L_DIRT_PATH_SIDE: u32 = 96;
pub const L_FARMLAND: u32 = 97;
pub const L_FARMLAND_MOIST: u32 = 98;

/// Demo block IDs (the synthetic world's palette; kept for M2b regression tests).
pub const B_STONE: i32 = 1;
pub const B_DIRT: i32 = 2;
pub const B_GRASS: i32 = 3;
pub const B_SAND: i32 = 4;
pub const B_LOG: i32 = 5;
pub const B_LEAVES: i32 = 6;
pub const B_PLANKS: i32 = 7;
pub const B_COBBLE: i32 = 8;
pub const B_BEDROCK: i32 = 9;
pub const B_COAL_ORE: i32 = 10;
pub const B_IRON_ORE: i32 = 11;
pub const B_GRAVEL: i32 = 12;
pub const B_SNOW: i32 = 13;
pub const B_GLASS: i32 = 14;
pub const B_SANDSTONE: i32 = 15;
pub const B_CLAY: i32 = 16;
pub const B_GLOWSTONE: i32 = 17;
pub const B_WATER: i32 = 18;
pub const B_LAVA: i32 = 19;
pub const B_ICE: i32 = 20;
pub const B_SHORT_GRASS: i32 = 21;
pub const B_POPPY: i32 = 22;
pub const B_DANDELION: i32 = 23;
pub const B_SAPLING: i32 = 24;
pub const B_DEEPSLATE: i32 = 25;
pub const B_ANDESITE: i32 = 26;
pub const B_DIORITE: i32 = 27;
pub const B_GRANITE: i32 = 28;
pub const B_TUFF: i32 = 29;
pub const B_CALCITE: i32 = 30;
pub const B_COPPER_ORE: i32 = 31;
pub const B_GOLD_ORE: i32 = 32;
pub const B_REDSTONE_ORE: i32 = 33;
pub const B_LAPIS_ORE: i32 = 34;
pub const B_DIAMOND_ORE: i32 = 35;
pub const B_EMERALD_ORE: i32 = 36;
pub const B_DEEPSLATE_COAL_ORE: i32 = 37;
pub const B_DEEPSLATE_IRON_ORE: i32 = 38;
pub const B_DEEPSLATE_COPPER_ORE: i32 = 39;
pub const B_DEEPSLATE_GOLD_ORE: i32 = 40;
pub const B_DEEPSLATE_REDSTONE_ORE: i32 = 41;
pub const B_DEEPSLATE_LAPIS_ORE: i32 = 42;
pub const B_DEEPSLATE_DIAMOND_ORE: i32 = 43;
pub const B_DEEPSLATE_EMERALD_ORE: i32 = 44;
pub const B_PODZOL: i32 = 45;
pub const B_MUD: i32 = 46;
pub const B_PACKED_ICE: i32 = 47;

#[derive(Clone, Copy)]
struct Material {
    layers: [u32; 6],
    cutout: bool,
    /// Fluid/ice surface: drawn in the blended pass after the opaque pass.
    translucent: bool,
    /// Plants use two crossed quads instead of a cube.
    cross: bool,
    /// Vanilla `getLightEmission` (0..=15). Torches/glowstone seed the block
    /// light flood fill with this.
    emission: u8,
    /// Vanilla `getLightBlock` (0..=15). Air is 0, leaves/water are 1, a full
    /// opaque cube is 15. Skylight loses this many levels when it enters.
    opacity: u8,
}

impl Material {
    const fn solid(layer: u32) -> Self {
        Self {
            layers: [layer; 6],
            cutout: false,
            translucent: false,
            cross: false,
            emission: 0,
            opacity: 15,
        }
    }

    const fn cutout(layer: u32) -> Self {
        Self {
            layers: [layer; 6],
            cutout: true,
            translucent: false,
            cross: false,
            emission: 0,
            opacity: 0,
        }
    }

    /// Water/ice: alpha-tested surface but still attenuates light a little.
    const fn fluid(layer: u32, opacity: u8) -> Self {
        Self {
            layers: [layer; 6],
            cutout: true,
            translucent: true,
            cross: false,
            emission: 0,
            opacity,
        }
    }

    /// Short grass/flowers: two crossed quads, no cube faces.
    const fn plant(layer: u32) -> Self {
        Self {
            layers: [layer; 6],
            cutout: true,
            translucent: false,
            cross: true,
            emission: 0,
            opacity: 0,
        }
    }

    /// Leaves/glass: alpha-tested, but still let skylight through with a small
    /// attenuation, exactly the value vanilla uses for foliage.
    const fn foliage(layer: u32) -> Self {
        Self {
            layers: [layer; 6],
            cutout: true,
            translucent: false,
            cross: false,
            emission: 0,
            opacity: 1,
        }
    }

    const fn emissive(layer: u32, emission: u8) -> Self {
        Self {
            layers: [layer; 6],
            cutout: false,
            translucent: false,
            cross: false,
            emission,
            opacity: 15,
        }
    }

    /// Vanilla `cube_column`: top/bottom use one tile, the four sides another.
    const fn column(top: u32, bottom: u32, side: u32) -> Self {
        Self {
            layers: [side, side, bottom, top, side, side],
            cutout: false,
            translucent: false,
            cross: false,
            emission: 0,
            opacity: 15,
        }
    }

    const fn column_top(top: u32, side: u32) -> Self {
        Self::column(top, top, side)
    }

    /// Vanilla grass block: green top, dirt bottom, side overlay.
    const fn grass() -> Self {
        Self::column(L_GRASS_TOP, L_DIRT, L_GRASS_SIDE)
    }
}

thread_local! {
    /// Real block-state IDs registered by the Brain, indexed by `Block.getId`.
    static REGISTERED: RefCell<HashMap<i32, Material>> = RefCell::new(HashMap::new());
}

/// Register a real block-state ID by its registry name (`minecraft:dirt`).
/// Called from the Java palette handshake in `bridge.js`.
#[wasm_bindgen]
pub fn set_block_name(block: i32, name: &str) {
    if let Some(material) = definition(name) {
        REGISTERED.with(|table| {
            table.borrow_mut().insert(block, material);
        });
    }
}

/// Register a material using explicit array layers (`-1` = no texture).
/// The page/Java can use this when it has already resolved model textures.
#[wasm_bindgen]
pub fn set_block_material(block: i32, top: i32, bottom: i32, side: i32, cutout: bool) {
    let pick = |value: i32, fallback: u32| {
        if value >= 0 {
            value as u32
        } else {
            fallback
        }
    };
    let material = Material {
        layers: [
            pick(side, L_STONE),
            pick(side, L_STONE),
            pick(bottom, L_DIRT),
            pick(top, L_STONE),
            pick(side, L_STONE),
            pick(side, L_STONE),
        ],
        cutout,
        translucent: false,
        cross: false,
        emission: 0,
        opacity: if cutout { 0 } else { 15 },
    };
    REGISTERED.with(|table| {
        table.borrow_mut().insert(block, material);
    });
}

#[wasm_bindgen]
pub fn clear_registered_materials() {
    REGISTERED.with(|table| table.borrow_mut().clear());
}

fn definition(raw: &str) -> Option<Material> {
    let mut name = raw.strip_prefix("minecraft:").unwrap_or(raw);
    if let Some(bracket) = name.find('[') {
        name = &name[..bracket];
    }
    let material = match name {
        // --- terrain ---------------------------------------------------------
        "stone" => Material::solid(L_STONE),
        "smooth_stone" => Material::solid(L_SMOOTH_STONE),
        "dirt" => Material::solid(L_DIRT),
        "grass_block" => Material::grass(),
        "sand" => Material::solid(L_SAND),
        "red_sand" => Material::solid(L_RED_SAND),
        "gravel" => Material::solid(L_GRAVEL),
        "clay" => Material::solid(L_CLAY),
        "mud" => Material::solid(L_MUD),
        "packed_mud" => Material::solid(L_PACKED_MUD),
        "mud_bricks" => Material::solid(L_MUD_BRICKS),
        "cobblestone" => Material::solid(L_COBBLE),
        "mossy_cobblestone" => Material::solid(L_MOSSY_COBBLE),
        "stone_bricks" => Material::solid(L_STONE_BRICKS),
        "bricks" => Material::solid(L_BRICKS),
        "terracotta" => Material::solid(L_TERRACOTTA),
        "andesite" => Material::solid(L_ANDESITE),
        "diorite" => Material::solid(L_DIORITE),
        "granite" => Material::solid(L_GRANITE),
        "deepslate" => Material::column_top(L_DEEPSLATE_TOP, L_DEEPSLATE),
        "tuff" => Material::solid(L_TUFF),
        "calcite" => Material::solid(L_CALCITE),
        "blackstone" => Material::solid(L_BLACKSTONE),
        "obsidian" => Material::solid(L_OBSIDIAN),
        "netherrack" => Material::solid(L_NETHERRACK),
        "soul_sand" => Material::solid(L_SOUL_SAND),
        "glowstone" => Material::emissive(L_GLOWSTONE, 15),
        "end_stone" => Material::solid(L_END_STONE),
        "purpur_block" => Material::solid(L_PURPUR),
        "ice" => Material::fluid(L_ICE, 1),
        "packed_ice" => Material::solid(L_PACKED_ICE),
        "blue_ice" => Material::solid(L_BLUE_ICE),
        "snow" | "snow_block" => Material::solid(L_SNOW),
        "powder_snow" => Material::solid(L_POWDER_SNOW),
        "moss_block" => Material::solid(L_MOSS_BLOCK),
        "sandstone" => Material::column(L_SANDSTONE_TOP, L_SANDSTONE_TOP, L_SANDSTONE),
        "red_sandstone" => Material::column_top(L_RED_SANDSTONE, L_RED_SANDSTONE),
        "ancient_debris" => Material::column(
            L_ANCIENT_DEBRIS_TOP,
            L_ANCIENT_DEBRIS_TOP,
            L_ANCIENT_DEBRIS_SIDE,
        ),
        "basalt" => Material::column(L_BASALT_TOP, L_BASALT_TOP, L_BASALT_SIDE),
        "podzol" => Material::column(L_PODZOL_TOP, L_DIRT, L_PODZOL_SIDE),
        "mycelium" => Material::column(L_MYCELIUM_TOP, L_DIRT, L_MYCELIUM_SIDE),
        "dirt_path" => Material::column(L_DIRT_PATH_TOP, L_DIRT, L_DIRT_PATH_SIDE),
        "farmland" => Material::column(L_FARMLAND, L_DIRT, L_DIRT),
        "farmland_moist" => Material::column(L_FARMLAND_MOIST, L_DIRT, L_DIRT),

        // --- wood ------------------------------------------------------------
        "oak_log" | "oak_wood" | "stripped_oak_log" | "stripped_oak_wood" => {
            Material::column_top(L_LOG_TOP, L_LOG)
        }
        "oak_leaves" => Material::foliage(L_LEAVES),
        "oak_planks" | "spruce_planks" | "birch_planks" | "jungle_planks" | "acacia_planks"
        | "dark_oak_planks" | "mangrove_planks" | "cherry_planks" | "bamboo_planks" => {
            Material::solid(L_PLANKS)
        }
        "bedrock" => Material::solid(L_BEDROCK),

        // --- ores and metal blocks -------------------------------------------
        "coal_ore" => Material::solid(L_COAL_ORE),
        "iron_ore" => Material::solid(L_IRON_ORE),
        "copper_ore" => Material::solid(L_COPPER_ORE),
        "gold_ore" => Material::solid(L_GOLD_ORE),
        "redstone_ore" => Material::solid(L_REDSTONE_ORE),
        "lapis_ore" => Material::solid(L_LAPIS_ORE),
        "diamond_ore" => Material::solid(L_DIAMOND_ORE),
        "emerald_ore" => Material::solid(L_EMERALD_ORE),
        "deepslate_coal_ore" => Material::solid(L_DEEPSLATE_COAL_ORE),
        "deepslate_iron_ore" => Material::solid(L_DEEPSLATE_IRON_ORE),
        "deepslate_copper_ore" => Material::solid(L_DEEPSLATE_COPPER_ORE),
        "deepslate_gold_ore" => Material::solid(L_DEEPSLATE_GOLD_ORE),
        "deepslate_redstone_ore" => Material::solid(L_DEEPSLATE_REDSTONE_ORE),
        "deepslate_lapis_ore" => Material::solid(L_DEEPSLATE_LAPIS_ORE),
        "deepslate_diamond_ore" => Material::solid(L_DEEPSLATE_DIAMOND_ORE),
        "deepslate_emerald_ore" => Material::solid(L_DEEPSLATE_EMERALD_ORE),
        "raw_iron_block" => Material::solid(L_RAW_IRON),
        "raw_copper_block" => Material::solid(L_RAW_COPPER),
        "raw_gold_block" => Material::solid(L_RAW_GOLD),
        "coal_block" => Material::solid(L_COAL_BLOCK),
        "iron_block" => Material::solid(L_IRON_BLOCK),
        "gold_block" => Material::solid(L_GOLD_BLOCK),
        "diamond_block" => Material::solid(L_DIAMOND_BLOCK),
        "emerald_block" => Material::solid(L_EMERALD_BLOCK),
        "lapis_block" => Material::solid(L_LAPIS_BLOCK),
        "redstone_block" => Material::solid(L_REDSTONE_BLOCK),
        "netherite_block" => Material::solid(L_NETHERITE_BLOCK),
        "amethyst_block" => Material::solid(L_AMETHYST),
        "budding_amethyst" => Material::solid(L_BUDDING_AMETHYST),
        "copper_block" => Material::solid(L_COPPER_BLOCK),
        "exposed_copper" => Material::solid(L_EXPOSED_COPPER),
        "weathered_copper" => Material::solid(L_WEATHERED_COPPER),
        "oxidized_copper" => Material::solid(L_OXIDIZED_COPPER),
        "cut_copper" => Material::solid(L_CUT_COPPER),

        // --- plants, glass, fluids -------------------------------------------
        "short_grass" | "tall_grass" | "fern" | "large_fern" => Material::plant(L_SHORT_GRASS),
        "poppy" => Material::plant(L_POPPY),
        "dandelion" => Material::plant(L_DANDELION),
        "oak_sapling" => Material::plant(L_SAPLING),
        "glass" | "glass_pane" => Material::cutout(L_GLASS),
        // Fluids/ice are drawn in the blended pass after the opaque pass.
        "water" | "flowing_water" => Material::fluid(L_WATER, 1),
        "lava" | "flowing_lava" => Material::solid(L_LAVA),

        // --- generic families ------------------------------------------------
        // The world contains every wood type and many plants. The 99-layer
        // array carries the oak variants, so map the family onto them rather
        // than falling back to stone-coloured unknown blocks.
        _ if name.ends_with("_log") || name.ends_with("_wood") => {
            Material::column_top(L_LOG_TOP, L_LOG)
        }
        _ if name.ends_with("_leaves") => Material::foliage(L_LEAVES),
        _ if name.ends_with("_planks") => Material::solid(L_PLANKS),
        _ if name.ends_with("_ore") => Material::solid(L_STONE),
        _ if name.ends_with("_sand") => Material::solid(L_SAND),
        _ if name.ends_with("_tulip") || name.ends_with("_flower") => Material::cutout(L_POPPY),
        _ if name.ends_with("_grass") || name.ends_with("_fern") => Material::cutout(L_SHORT_GRASS),
        _ => return None,
    };
    Some(material)
}

/// Vanilla directional shading: down 0.5, up 1.0, north/south 0.8, east/west 0.6.
pub fn face_shade(face: u8) -> u32 {
    let shade = match face {
        FACE_POS_Y => 1.0,
        FACE_NEG_Y => 0.5,
        FACE_POS_X | FACE_NEG_X => 0.6,
        _ => 0.8,
    };
    (shade * 255.0) as u32
}

/// Texture layer for one face. Registered real IDs win; the demo palette is the
/// fallback. Unknown IDs render as stone so a bridge mistake is visible but never
/// a hole in the world.
pub fn layer(block: i32, face: u8) -> u32 {
    if let Some(material) = REGISTERED.with(|table| table.borrow().get(&block).copied()) {
        return material.layers[(face & 7) as usize % 6];
    }
    demo_layer(block, face)
}

fn demo_layer(block: i32, face: u8) -> u32 {
    let top = face == FACE_POS_Y;
    let bottom = face == FACE_NEG_Y;
    match block {
        B_STONE => L_STONE,
        B_DIRT => L_DIRT,
        B_GRASS => {
            if top {
                L_GRASS_TOP
            } else if bottom {
                L_DIRT
            } else {
                L_GRASS_SIDE
            }
        }
        B_SAND => L_SAND,
        B_LOG => {
            if top || bottom {
                L_LOG_TOP
            } else {
                L_LOG
            }
        }
        B_LEAVES => L_LEAVES,
        B_PLANKS => L_PLANKS,
        B_COBBLE => L_COBBLE,
        B_BEDROCK => L_BEDROCK,
        B_COAL_ORE => L_COAL_ORE,
        B_IRON_ORE => L_IRON_ORE,
        B_GRAVEL => L_GRAVEL,
        B_SNOW => L_SNOW,
        B_GLASS => L_GLASS,
        B_SANDSTONE => {
            if top || bottom {
                L_SANDSTONE_TOP
            } else {
                L_SANDSTONE
            }
        }
        B_CLAY => L_CLAY,
        B_GLOWSTONE => L_GLOWSTONE,
        B_WATER => L_WATER,
        B_LAVA => L_LAVA,
        B_ICE => L_ICE,
        B_SHORT_GRASS => L_SHORT_GRASS,
        B_POPPY => L_POPPY,
        B_DANDELION => L_DANDELION,
        B_SAPLING => L_SAPLING,
        B_DEEPSLATE => {
            if top || bottom { L_DEEPSLATE_TOP } else { L_DEEPSLATE }
        }
        B_ANDESITE => L_ANDESITE,
        B_DIORITE => L_DIORITE,
        B_GRANITE => L_GRANITE,
        B_TUFF => L_TUFF,
        B_CALCITE => L_CALCITE,
        B_COPPER_ORE => L_COPPER_ORE,
        B_GOLD_ORE => L_GOLD_ORE,
        B_REDSTONE_ORE => L_REDSTONE_ORE,
        B_LAPIS_ORE => L_LAPIS_ORE,
        B_DIAMOND_ORE => L_DIAMOND_ORE,
        B_EMERALD_ORE => L_EMERALD_ORE,
        B_DEEPSLATE_COAL_ORE => L_DEEPSLATE_COAL_ORE,
        B_DEEPSLATE_IRON_ORE => L_DEEPSLATE_IRON_ORE,
        B_DEEPSLATE_COPPER_ORE => L_DEEPSLATE_COPPER_ORE,
        B_DEEPSLATE_GOLD_ORE => L_DEEPSLATE_GOLD_ORE,
        B_DEEPSLATE_REDSTONE_ORE => L_DEEPSLATE_REDSTONE_ORE,
        B_DEEPSLATE_LAPIS_ORE => L_DEEPSLATE_LAPIS_ORE,
        B_DEEPSLATE_DIAMOND_ORE => L_DEEPSLATE_DIAMOND_ORE,
        B_DEEPSLATE_EMERALD_ORE => L_DEEPSLATE_EMERALD_ORE,
        B_PODZOL => {
            if top {
                L_PODZOL_TOP
            } else if bottom {
                L_DIRT
            } else {
                L_PODZOL_SIDE
            }
        }
        B_MUD => L_MUD,
        B_PACKED_ICE => L_PACKED_ICE,
        _ => L_STONE,
    }
}

/// Vanilla `getLightEmission` for a block ID (0 for everything unregistered).
pub fn emission(block: i32) -> u8 {
    if let Some(material) = REGISTERED.with(|table| table.borrow().get(&block).copied()) {
        return material.emission;
    }
    match block {
        B_GLOWSTONE => 15,
        B_LAVA => 15,
        _ => 0,
    }
}

/// Vanilla `getLightBlock`: how many light levels a block eats. Air is 0;
/// leaves are 1; a full cube is 15.
pub fn opacity(block: i32) -> u8 {
    if block == 0 {
        return 0;
    }
    if let Some(material) = REGISTERED.with(|table| table.borrow().get(&block).copied()) {
        return material.opacity;
    }
    match block {
        B_LEAVES => 1,
        B_GLASS => 0,
        B_WATER => 1,
        B_ICE => 1,
        B_PACKED_ICE => 1,
        B_SHORT_GRASS | B_POPPY | B_DANDELION | B_SAPLING => 0,
        _ => 15,
    }
}

/// Water/lava: not a walkable floor and not registered as a full cube.
pub fn is_fluid(block: i32) -> bool {
    matches!(block, B_WATER | B_LAVA)
}

/// Two-quad plants must not be treated as a solid neighbour when culling cube
/// faces, and the mesher emits their crossed quads separately.
pub fn is_cross(block: i32) -> bool {
    if let Some(material) = REGISTERED.with(|table| table.borrow().get(&block).copied()) {
        return material.cross;
    }
    matches!(
        block,
        B_SHORT_GRASS | B_POPPY | B_DANDELION | B_SAPLING
    )
}

/// Blocks that must be alpha-tested (leaves, glass, plants). Everything else can
/// skip the branch, but one shader with a test keeps the pipeline set small.
/// Render class for the mesher/pipeline split.
pub fn class(block: i32) -> u32 {
    if is_translucent(block) {
        2
    } else if is_cutout(block) {
        1
    } else {
        0
    }
}

pub fn is_translucent(block: i32) -> bool {
    if let Some(material) = REGISTERED.with(|table| table.borrow().get(&block).copied()) {
        return material.translucent;
    }
    matches!(block, B_WATER | B_ICE)
}

/// Blocks that must be alpha-tested (leaves, glass, plants). Everything else can
/// skip the branch, but one shader with a test keeps the pipeline set small.
pub fn is_cutout(block: i32) -> bool {
    if let Some(material) = REGISTERED.with(|table| table.borrow().get(&block).copied()) {
        return material.cutout;
    }
    matches!(
        block,
        B_LEAVES | B_GLASS | B_WATER | B_ICE | B_SHORT_GRASS | B_POPPY | B_DANDELION | B_SAPLING
    )
}
