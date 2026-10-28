//! Block-state -> texture layer + vanilla face shading.
//!
//! This is the Body's only interpretation of a block ID. The demo IDs below are
//! temporary; the Java Brain will send the real `blockStateId -> {layer, flags}`
//! table at startup (see docs/RENDERER.md §5.4) and this module becomes a lookup.

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

/// Demo block IDs (the synthetic world's palette).
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

/// Texture layer for one face of a demo block. Unknown IDs render as stone so a
/// bridge mistake is visible but never a hole in the world.
pub fn layer(block: i32, face: u8) -> u32 {
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
        _ => L_STONE,
    }
}

/// Blocks that must be alpha-tested (leaves, glass). Everything else can skip
/// the branch, but one shader with a test keeps the pipeline set small.
pub fn is_cutout(block: i32) -> bool {
    matches!(block, B_LEAVES | B_GLASS)
}
