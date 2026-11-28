//! Native world generation and section meshing.
//!
//! This is deliberately a compact, deterministic overworld: rolling grass
//! hills, sand shorelines, shallow water, oak trees and a bedrock floor. It
//! exercises the same 16³ section mesher and vanilla texture palette as the
//! browser body, but the data is generated in Rust instead of arriving from the
//! Java Brain. The next milestone can replace this generator with the real
//! Java world snapshot without touching the renderer.

use std::collections::HashMap;

use crate::arena::SectionMeshData;
use crate::material;
use crate::mesh;

pub const SECTION: i32 = 16;
pub const WORLD_RADIUS: i32 = 4;
pub const WORLD_HEIGHT: i32 = 4;
pub const WATER_LEVEL: i32 = 24;
pub const WORLD_MAX_Y: i32 = WORLD_HEIGHT * SECTION;

type Key = (i32, i32, i32);

pub struct World {
    pub meshes: Vec<SectionMeshData>,
    pub spawn: [f32; 3],
    pub spawn_yaw: f32,
}

pub fn build() -> World {
    register_blocks();

    let mut blocks: HashMap<Key, Vec<i32>> = HashMap::new();
    let mut lights: HashMap<Key, Vec<u8>> = HashMap::new();
    let tree_blocks = tree_blocks();

    for sx in -WORLD_RADIUS..=WORLD_RADIUS {
        for sz in -WORLD_RADIUS..=WORLD_RADIUS {
            for sy in 0..WORLD_HEIGHT {
                let mut center = vec![0i32; mesh::VOLUME];
                let mut light = vec![0u8; mesh::VOLUME];
                for y in 0..SECTION {
                    for z in 0..SECTION {
                        for x in 0..SECTION {
                            let wx = sx * SECTION + x;
                            let wy = sy * SECTION + y;
                            let wz = sz * SECTION + z;
                            let block = block_at(wx, wy, wz, &tree_blocks);
                            let index = mesh::block_index(x as usize, y as usize, z as usize);
                            center[index] = block;
                            // Air and water transmit full skylight. Solid blocks
                            // are dark; the mesher samples the adjacent air cell
                            // for each face, so exposed faces stay bright.
                            light[index] = if block == 0 || block == material::B_WATER {
                                0xF0
                            } else {
                                0
                            };
                        }
                    }
                }
                blocks.insert((sx, sy, sz), center);
                lights.insert((sx, sy, sz), light);
            }
        }
    }

    let mut meshes = Vec::new();
    for sx in -WORLD_RADIUS..=WORLD_RADIUS {
        for sz in -WORLD_RADIUS..=WORLD_RADIUS {
            for sy in 0..WORLD_HEIGHT {
                let key = (sx, sy, sz);
                let Some(center) = blocks.get(&key) else { continue };
                let Some(center_light) = lights.get(&key) else { continue };
                let mut neighbors: [Option<&[i32]>; 6] = [None; 6];
                let mut neighbor_lights: [Option<&[u8]>; 6] = [None; 6];
                let offsets = [
                    (-1, 0, 0),
                    (1, 0, 0),
                    (0, -1, 0),
                    (0, 1, 0),
                    (0, 0, -1),
                    (0, 0, 1),
                ];
                for (slot, (dx, dy, dz)) in offsets.iter().enumerate() {
                    let neighbor = (sx + dx, sy + dy, sz + dz);
                    neighbors[slot] = blocks.get(&neighbor).map(|v| v.as_slice());
                    neighbor_lights[slot] = lights.get(&neighbor).map(|v| v.as_slice());
                }
                if let Some(section) =
                    mesh::mesh_section(sx, sy, sz, center, center_light, &neighbors, &neighbor_lights)
                {
                    meshes.push(SectionMeshData {
                        key,
                        vertices: section.vertices,
                        indices: section.indices,
                        opaque_count: section.opaque_count,
                        min: [
                            (sx * SECTION) as f32,
                            (sy * SECTION) as f32,
                            (sz * SECTION) as f32,
                        ],
                        max: [
                            ((sx + 1) * SECTION) as f32,
                            ((sy + 1) * SECTION) as f32,
                            ((sz + 1) * SECTION) as f32,
                        ],
                    });
                }
            }
        }
    }

    let spawn_surface = surface_y(8, 8, &tree_blocks);
    World {
        meshes,
        spawn: [8.5, (spawn_surface.max(WATER_LEVEL + 1) + 1) as f32, 8.5],
        spawn_yaw: 0.0,
    }
}

fn register_blocks() {
    for (id, name) in [
        (material::B_STONE, "stone"),
        (material::B_DIRT, "dirt"),
        (material::B_GRASS, "grass_block"),
        (material::B_SAND, "sand"),
        (material::B_LOG, "oak_log"),
        (material::B_LEAVES, "oak_leaves"),
        (material::B_PLANKS, "oak_planks"),
        (material::B_COBBLE, "cobblestone"),
        (material::B_BEDROCK, "bedrock"),
        (material::B_COAL_ORE, "coal_ore"),
        (material::B_IRON_ORE, "iron_ore"),
        (material::B_GRAVEL, "gravel"),
        (material::B_WATER, "water"),
        (material::B_GLASS, "glass"),
        (material::B_SHORT_GRASS, "short_grass"),
        (material::B_POPPY, "poppy"),
        (material::B_DANDELION, "dandelion"),
        (material::B_SAPLING, "oak_sapling"),
    ] {
        material::set_block_name(id, name);
    }
}

fn block_at(x: i32, y: i32, z: i32, trees: &HashMap<(i32, i32, i32), i32>) -> i32 {
    if y < 0 || y >= WORLD_MAX_Y {
        return 0;
    }
    if let Some(block) = trees.get(&(x, y, z)) {
        return *block;
    }
    let surface = terrain_height(x, z);
    if y == 0 {
        return material::B_BEDROCK;
    }
    if y < surface - 4 {
        return if hash3(x, y, z) % 97 == 0 {
            material::B_COAL_ORE
        } else {
            material::B_STONE
        };
    }
    if y < surface - 1 {
        return material::B_DIRT;
    }
    if y == surface - 1 {
        return if surface <= WATER_LEVEL + 1 {
            material::B_SAND
        } else {
            material::B_GRASS
        };
    }
    if y <= WATER_LEVEL {
        return material::B_WATER;
    }
    0
}

fn surface_y(x: i32, z: i32, trees: &HashMap<(i32, i32, i32), i32>) -> i32 {
    let mut top = terrain_height(x, z);
    for y in (0..WORLD_MAX_Y).rev() {
        let block = block_at(x, y, z, trees);
        if block != 0 && block != material::B_WATER {
            top = y + 1;
            break;
        }
    }
    top
}

pub fn terrain_height(x: i32, z: i32) -> i32 {
    let a = ((x as f32 * 0.045).sin() * 3.0) as i32;
    let b = ((z as f32 * 0.037).cos() * 2.5) as i32;
    let c = (((x + z) as f32 * 0.021).sin() * 4.0) as i32;
    (27 + a + b + c).clamp(6, WORLD_MAX_Y - 4)
}

fn tree_blocks() -> HashMap<(i32, i32, i32), i32> {
    let mut out = HashMap::new();
    let min = -WORLD_RADIUS * SECTION - 3;
    let max = WORLD_RADIUS * SECTION + 3;
    for z in min..=max {
        for x in min..=max {
            if hash2(x, z) % 100 >= 3 {
                continue;
            }
            let surface = terrain_height(x, z);
            if surface <= WATER_LEVEL + 1 || surface >= WORLD_MAX_Y - 8 {
                continue;
            }
            // Keep the spawn clearing readable and the camera out of a trunk.
            if (x - 8).abs() < 5 && (z - 8).abs() < 5 {
                continue;
            }
            let height = 4 + (hash2(x + 11, z - 7) % 3) as i32;
            for y in surface..surface + height {
                out.insert((x, y, z), material::B_LOG);
            }
            let top = surface + height;
            for dy in -2..=2 {
                for dz in -2..=2 {
                    for dx in -2..=2 {
                        if dx.abs() == 2 && dz.abs() == 2 {
                            continue;
                        }
                        if dy == 2 && (dx.abs() == 2 || dz.abs() == 2) {
                            continue;
                        }
                        let leaf = (x + dx, top + dy, z + dz);
                        if leaf.1 >= WORLD_MAX_Y || out.contains_key(&leaf) {
                            continue;
                        }
                        out.insert(leaf, material::B_LEAVES);
                    }
                }
            }
        }
    }
    out
}

fn hash2(x: i32, z: i32) -> u32 {
    let mut h = (x as u32).wrapping_mul(0x9E37_79B9) ^ (z as u32).wrapping_mul(0x85EB_CA6B);
    h ^= h >> 13;
    h = h.wrapping_mul(0xC2B2_AE35);
    h ^ (h >> 16)
}

fn hash3(x: i32, y: i32, z: i32) -> u32 {
    let mut h = hash2(x, z) ^ (y as u32).wrapping_mul(0x27D4_EB2F);
    h ^= h >> 15;
    h = h.wrapping_mul(0x1656_67B1);
    h ^ (h >> 13)
}
