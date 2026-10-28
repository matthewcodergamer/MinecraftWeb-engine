//! Bridge v0: the Java Brain writes 16^3 block-ID sections here.
//!
//! `upload_section(cx, cy, cz, version, ids)` is the wasm-bindgen export the
//! Brain will call. For M1, `generate_demo()` feeds the same store with a
//! synthetic island so the mesher can be measured before Java is wired in.

use std::cell::RefCell;
use std::collections::{HashMap, VecDeque};

use wasm_bindgen::prelude::*;

use crate::mesh::{block_index, mesh_section, Vertex, VOLUME};

struct Section {
    version: u32,
    ids: Vec<i32>,
}

thread_local! {
    static SECTIONS: RefCell<HashMap<(i32, i32, i32), Section>> =
        RefCell::new(HashMap::new());
    /// Sections waiting to be re-meshed, in upload order. Deduplicated, because
    /// one edit dirties the section plus up to six neighbours.
    static DIRTY: RefCell<VecDeque<(i32, i32, i32)>> = const { RefCell::new(VecDeque::new()) };
}

/// Bridge v0 entry point. `ids` must be `Int32Array(4096)`, indexed
/// `y*256 + z*16 + x`, with 0 = air. Returns false on a wrong-sized buffer.
#[wasm_bindgen]
pub fn upload_section(cx: i32, cy: i32, cz: i32, version: u32, ids: &[i32]) -> bool {
    if ids.len() != VOLUME {
        return false;
    }
    store(cx, cy, cz, version, ids);
    mark_dirty(cx, cy, cz);
    for (dx, dy, dz) in [
        (-1, 0, 0),
        (1, 0, 0),
        (0, -1, 0),
        (0, 1, 0),
        (0, 0, -1),
        (0, 0, 1),
    ] {
        let key = (cx + dx, cy + dy, cz + dz);
        let exists = SECTIONS.with(|cell| cell.borrow().contains_key(&key));
        if exists {
            mark_dirty(key.0, key.1, key.2);
        }
    }
    true
}

fn mark_dirty(cx: i32, cy: i32, cz: i32) {
    let key = (cx, cy, cz);
    DIRTY.with(|queue| {
        let mut queue = queue.borrow_mut();
        if !queue.contains(&key) {
            queue.push_back(key);
        }
    });
}

/// Next section the render loop should re-mesh (time-sliced), or `None`.
pub fn next_dirty() -> Option<(i32, i32, i32)> {
    DIRTY.with(|queue| queue.borrow_mut().pop_front())
}

fn store(cx: i32, cy: i32, cz: i32, version: u32, ids: &[i32]) {
    SECTIONS.with(|cell| {
        cell.borrow_mut().insert(
            (cx, cy, cz),
            Section {
                version,
                ids: ids.to_vec(),
            },
        );
    });
}

#[derive(Default, Clone, Copy)]
pub struct WorldStats {
    pub sections: u32,
    pub faces: u64,
    pub quads: u64,
    pub vertices: u64,
    pub indices: u64,
}

pub struct SectionMeshData {
    pub key: (i32, i32, i32),
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
    pub min: [f32; 3],
    pub max: [f32; 3],
}

pub struct WorldMesh {
    pub sections: Vec<SectionMeshData>,
    pub stats: WorldStats,
}

/// Meshes every stored section once. Neighbouring sections feed the border
/// assembly; missing neighbours are treated as air. Each section stays separate
/// so the arena can assign it an index range and cull it independently.
pub fn mesh_world() -> WorldMesh {
    let mut result = WorldMesh {
        sections: Vec::new(),
        stats: WorldStats::default(),
    };

    SECTIONS.with(|cell| {
        let sections = cell.borrow();
        let mut keys: Vec<(i32, i32, i32)> = sections.keys().copied().collect();
        keys.sort_unstable();
        result.stats.sections = keys.len() as u32;
        for key in keys {
            let (cx, cy, cz) = key;
            let center = &sections[&key];
            let neighbors = [
                sections.get(&(cx - 1, cy, cz)).map(|s| s.ids.as_slice()),
                sections.get(&(cx + 1, cy, cz)).map(|s| s.ids.as_slice()),
                sections.get(&(cx, cy - 1, cz)).map(|s| s.ids.as_slice()),
                sections.get(&(cx, cy + 1, cz)).map(|s| s.ids.as_slice()),
                sections.get(&(cx, cy, cz - 1)).map(|s| s.ids.as_slice()),
                sections.get(&(cx, cy, cz + 1)).map(|s| s.ids.as_slice()),
            ];

            if let Some(mesh) = mesh_section(cx, cy, cz, &center.ids, &neighbors) {
                result.stats.faces += mesh.faces;
                result.stats.quads += mesh.quads;
                result.stats.vertices += mesh.vertices.len() as u64;
                result.stats.indices += mesh.indices.len() as u64;

                let min = [(cx * 16) as f32, (cy * 16) as f32, (cz * 16) as f32];
                result.sections.push(SectionMeshData {
                    key,
                    vertices: mesh.vertices,
                    indices: mesh.indices,
                    min,
                    max: [min[0] + 16.0, min[1] + 16.0, min[2] + 16.0],
                });
            }
        }
    });

    result
}

/// Re-meshes one section (used by the incremental path after `upload_section`).
/// An empty mesh is returned for a section that became all-air, so the arena can
/// drop its draw range.
pub fn remesh(key: (i32, i32, i32)) -> Option<SectionMeshData> {
    let (cx, cy, cz) = key;
    let (center, neighbors) = SECTIONS.with(|cell| {
        let sections = cell.borrow();
        let center = sections.get(&key)?.ids.clone();
        let neighbors = [
            sections.get(&(cx - 1, cy, cz)).map(|s| s.ids.clone()),
            sections.get(&(cx + 1, cy, cz)).map(|s| s.ids.clone()),
            sections.get(&(cx, cy - 1, cz)).map(|s| s.ids.clone()),
            sections.get(&(cx, cy + 1, cz)).map(|s| s.ids.clone()),
            sections.get(&(cx, cy, cz - 1)).map(|s| s.ids.clone()),
            sections.get(&(cx, cy, cz + 1)).map(|s| s.ids.clone()),
        ];
        Some((center, neighbors))
    })?;

    let borrowed: [Option<&[i32]>; 6] = [
        neighbors[0].as_deref(),
        neighbors[1].as_deref(),
        neighbors[2].as_deref(),
        neighbors[3].as_deref(),
        neighbors[4].as_deref(),
        neighbors[5].as_deref(),
    ];

    let min = [(cx * 16) as f32, (cy * 16) as f32, (cz * 16) as f32];
    Some(match mesh_section(cx, cy, cz, &center, &borrowed) {
        Some(mesh) => SectionMeshData {
            key,
            vertices: mesh.vertices,
            indices: mesh.indices,
            min,
            max: [min[0] + 16.0, min[1] + 16.0, min[2] + 16.0],
        },
        None => SectionMeshData {
            key,
            vertices: Vec::new(),
            indices: Vec::new(),
            min,
            max: [min[0] + 16.0, min[1] + 16.0, min[2] + 16.0],
        },
    })
}

/// Synthetic 8x8 chunk island, two section layers high. Flat-ish sine terrain
/// so greedy merging has real work to do. Trees and ores exercise the texture
/// array and cutout path; none of this is worldgen — Java owns that (M3).
pub fn generate_demo() {
    const CHUNKS: i32 = 8;
    const LAYERS: i32 = 2;

    SECTIONS.with(|cell| cell.borrow_mut().clear());

    for cz in 0..CHUNKS {
        for cx in 0..CHUNKS {
            for cy in 0..LAYERS {
                let mut ids = vec![0i32; VOLUME];

                for y in 0..16usize {
                    for z in 0..16usize {
                        for x in 0..16usize {
                            let world_x = cx * 16 + x as i32;
                            let world_z = cz * 16 + z as i32;
                            let world_y = cy * 16 + y as i32;
                            let height = terrain_height(world_x, world_z);

                            if world_y < height {
                                let depth = height - 1 - world_y;
                                ids[block_index(x, y, z)] = if depth == 0 {
                                    if height >= 21 {
                                        crate::material::B_SNOW
                                    } else if height <= 8 {
                                        crate::material::B_SAND
                                    } else {
                                        crate::material::B_GRASS
                                    }
                                } else if depth <= 3 {
                                    crate::material::B_DIRT
                                } else {
                                    ore_or_stone(world_x, world_y, world_z, depth)
                                };
                            }
                        }
                    }
                }

                store(cx, cy, cz, 1, &ids);
            }
        }
    }

    plant_trees(CHUNKS, LAYERS);
}

fn ore_or_stone(x: i32, y: i32, z: i32, depth: i32) -> i32 {
    let h = hash3(x, y, z);
    if depth > 4 {
        if h % 97 == 0 {
            return crate::material::B_COAL_ORE;
        }
        if h % 143 == 0 {
            return crate::material::B_IRON_ORE;
        }
        if h % 211 == 0 {
            return crate::material::B_GRAVEL;
        }
    }
    crate::material::B_STONE
}

/// Deterministic 3D hash (the demo has no seed — Java's seed is authoritative).
fn hash3(x: i32, y: i32, z: i32) -> u32 {
    let mut h = (x as u32)
        .wrapping_mul(0x9E37_79B1)
        ^ (y as u32).wrapping_mul(0x85EB_CA77)
        ^ (z as u32).wrapping_mul(0xC2B2_AE3D);
    h ^= h >> 15;
    h = h.wrapping_mul(0x2545_F491);
    h ^= h >> 13;
    h
}

fn plant_trees(chunks: i32, layers: i32) {
    for cz in 0..chunks {
        for cx in 0..chunks {
            for i in 0..2u32 {
                let h = hash3(cx, i as i32, cz);
                let x = cx * 16 + 3 + (h % 10) as i32;
                let z = cz * 16 + 3 + ((h >> 8) % 10) as i32;
                let ground = terrain_height(x, z);
                if !(12..=20).contains(&ground) {
                    continue; // keep beaches and peaks clear
                }
                let trunk = 4 + ((h >> 16) % 3) as i32;
                for dy in 0..trunk {
                    put(x, ground + dy, z, crate::material::B_LOG, layers);
                }
                // 5x5x3 canopy, skipping the corners like vanilla oak.
                for dy in 0..3 {
                    let radius: i32 = if dy == 2 { 1 } else { 2 };
                    for dz in -radius..=radius {
                        for dx in -radius..=radius {
                            if dx.abs() == radius && dz.abs() == radius && dy == 2 {
                                continue;
                            }
                            put(
                                x + dx,
                                ground + trunk - 1 + dy,
                                z + dz,
                                crate::material::B_LEAVES,
                                layers,
                            );
                        }
                    }
                }
            }
        }
    }
}

fn put(x: i32, y: i32, z: i32, block: i32, layers: i32) {
    if y < 0 || y >= layers * 16 {
        return;
    }
    let key = (x >> 4, y >> 4, z >> 4);
    SECTIONS.with(|cell| {
        let mut sections = cell.borrow_mut();
        if let Some(section) = sections.get_mut(&key) {
            let index = block_index((x & 15) as usize, (y & 15) as usize, (z & 15) as usize);
            section.ids[index] = block;
        }
    });
}

fn terrain_height(x: i32, z: i32) -> i32 {
    let fx = x as f32;
    let fz = z as f32;
    let height = 10.0
        + (fx * 0.28).sin() * 3.0
        + (fz * 0.23).cos() * 2.5
        + ((fx + fz) * 0.11).sin() * 1.5;
    (height.round() as i32).clamp(4, 22)
}
