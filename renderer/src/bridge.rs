//! Bridge v0: the Java Brain writes 16^3 block-ID sections here.
//!
//! `upload_section(cx, cy, cz, version, ids)` is the wasm-bindgen export the
//! Brain calls. For the renderer-only deployment, `generate_demo()` feeds the
//! same store with Horizon-derived noise terrain so the mesher and light
//! engine are exercised before Java is wired in.

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, VecDeque};

use wasm_bindgen::prelude::*;

use crate::lighting;
use crate::mesh::{block_index, mesh_section, Vertex, VOLUME};
use crate::worldgen::{WorldGen, CHUNK_RADIUS, LAYERS, SEA_LEVEL, WORLD_TOP};

const DEMO_SEED: i64 = 0x5eed;

struct Section {
    version: u32,
    ids: Vec<i32>,
    /// Packed `sky << 4 | block`, 4096 bytes.
    light: Vec<u8>,
}

thread_local! {
    static SECTIONS: RefCell<HashMap<(i32, i32, i32), Section>> =
        RefCell::new(HashMap::new());
    /// Light that arrived before its section (Java sends asynchronously).
    static PENDING_LIGHT: RefCell<HashMap<(i32, i32, i32), Vec<u8>>> =
        RefCell::new(HashMap::new());
    /// Sections waiting to be re-meshed, in upload order. Deduplicated, because
    /// one edit dirties the section plus up to six neighbours.
    static DIRTY: RefCell<VecDeque<(i32, i32, i32)>> = const { RefCell::new(VecDeque::new()) };
    /// Set when the Brain takes ownership of the world. The renderer drops the
    /// synthetic demo arena once, before the first real section is drawn.
    static RESET: Cell<bool> = const { Cell::new(false) };
    static VERSION: Cell<u32> = const { Cell::new(1) };
    static JAVA_CONNECTED: Cell<bool> = const { Cell::new(false) };
    /// Java sent authoritative light; do not overwrite it with the BFS below.
    static JAVA_LIGHT: Cell<bool> = const { Cell::new(false) };
    static LIGHT_STALE: Cell<bool> = const { Cell::new(false) };
}

#[wasm_bindgen]
pub fn upload_section(cx: i32, cy: i32, cz: i32, version: u32, ids: &[i32]) -> bool {
    if ids.len() != VOLUME {
        return false;
    }
    store(cx, cy, cz, version, ids);
    if !JAVA_LIGHT.with(|flag| flag.get()) {
        LIGHT_STALE.with(|flag| flag.set(true));
    }
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

/// Exact bridge name the JavaScript glue calls for a 16x16x16 chunk slice:
///
/// ```js
/// body.receive_java_chunk(chunkX, chunkZ, int32Array4096);
/// ```
///
/// The first real chunk retires the synthetic demo world. `cy` is implicitly 0;
/// worlds with a non-zero minimum Y use `receive_java_section` instead.
#[wasm_bindgen]
pub fn receive_java_chunk(chunk_x: i32, chunk_z: i32, block_ids: &[i32]) -> bool {
    receive_java_section(chunk_x, 0, chunk_z, block_ids)
}

/// Section-level form used by the full Java capture hook (16³ IDs plus cy).
#[wasm_bindgen]
pub fn receive_java_section(cx: i32, cy: i32, cz: i32, block_ids: &[i32]) -> bool {
    if block_ids.len() != VOLUME {
        console_error_panic_hook::set_once();
        web_sys::console::warn_1(&JsValue::from_str(&format!(
            "receive_java_section: expected {VOLUME} IDs, got {}",
            block_ids.len()
        )));
        return false;
    }
    if !JAVA_CONNECTED.with(|flag| flag.replace(true)) {
        // First real section: the demo world is no longer authoritative.
        SECTIONS.with(|cell| cell.borrow_mut().clear());
        DIRTY.with(|queue| queue.borrow_mut().clear());
        PENDING_LIGHT.with(|cell| cell.borrow_mut().clear());
        JAVA_LIGHT.with(|flag| flag.set(false));
        LIGHT_STALE.with(|flag| flag.set(false));
        RESET.with(|flag| flag.set(true));
    }
    let version = VERSION.with(|value| {
        let next = value.get().wrapping_add(1);
        value.set(next);
        next
    });
    upload_section(cx, cy, cz, version, block_ids)
}

/// Java → Body packed skylight/blocklight for one section.
///
/// Java can obtain the exact vanilla values with
/// `Level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(pos)`
/// (and the same for `BLOCK`), packing each nibble as `sky << 4 | block`.
#[wasm_bindgen]
pub fn receive_java_light(cx: i32, cy: i32, cz: i32, packed: &[u8]) -> bool {
    if packed.len() != VOLUME {
        return false;
    }
    JAVA_LIGHT.with(|flag| flag.set(true));
    LIGHT_STALE.with(|flag| flag.set(false));
    let key = (cx, cy, cz);
    let stored = SECTIONS.with(|cell| {
        let mut sections = cell.borrow_mut();
        if let Some(section) = sections.get_mut(&key) {
            section.light = packed.to_vec();
            true
        } else {
            false
        }
    });
    if !stored {
        PENDING_LIGHT.with(|cell| {
            cell.borrow_mut().insert(key, packed.to_vec());
        });
    }
    mark_dirty(cx, cy, cz);
    for (dx, dy, dz) in [
        (-1, 0, 0),
        (1, 0, 0),
        (0, -1, 0),
        (0, 1, 0),
        (0, 0, -1),
        (0, 0, 1),
    ] {
        mark_dirty(cx + dx, cy + dy, cz + dz);
    }
    true
}

/// True exactly once after the Brain's first section arrives.
pub fn take_reset() -> bool {
    RESET.with(|flag| flag.replace(false))
}

#[wasm_bindgen]
pub fn reset_world() {
    SECTIONS.with(|cell| cell.borrow_mut().clear());
    PENDING_LIGHT.with(|cell| cell.borrow_mut().clear());
    DIRTY.with(|queue| queue.borrow_mut().clear());
    JAVA_CONNECTED.with(|flag| flag.set(false));
    JAVA_LIGHT.with(|flag| flag.set(false));
    LIGHT_STALE.with(|flag| flag.set(false));
    RESET.with(|flag| flag.set(true));
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

pub fn next_dirty() -> Option<(i32, i32, i32)> {
    DIRTY.with(|queue| queue.borrow_mut().pop_front())
}

/// Drops pending remesh work after a full `mesh_world` pass.
pub fn clear_dirty() {
    DIRTY.with(|queue| queue.borrow_mut().clear());
}

fn store(cx: i32, cy: i32, cz: i32, version: u32, ids: &[i32]) {
    let key = (cx, cy, cz);
    let pending = PENDING_LIGHT.with(|cell| cell.borrow_mut().remove(&key));
    SECTIONS.with(|cell| {
        let mut sections = cell.borrow_mut();
        if let Some(section) = sections.get_mut(&key) {
            section.version = version;
            section.ids.copy_from_slice(ids);
            if let Some(light) = pending {
                section.light = light;
            }
        } else {
            sections.insert(
                key,
                Section {
                    version,
                    ids: ids.to_vec(),
                    light: pending.unwrap_or_else(|| vec![0xF0; VOLUME]),
                },
            );
        }
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
    /// Leading indices for the opaque/cutout pass; the rest are blended.
    pub opaque_count: u32,
    pub min: [f32; 3],
    pub max: [f32; 3],
}

pub struct WorldMesh {
    pub sections: Vec<SectionMeshData>,
    pub stats: WorldStats,
}

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
            let neighbor_lights = [
                sections.get(&(cx - 1, cy, cz)).map(|s| s.light.as_slice()),
                sections.get(&(cx + 1, cy, cz)).map(|s| s.light.as_slice()),
                sections.get(&(cx, cy - 1, cz)).map(|s| s.light.as_slice()),
                sections.get(&(cx, cy + 1, cz)).map(|s| s.light.as_slice()),
                sections.get(&(cx, cy, cz - 1)).map(|s| s.light.as_slice()),
                sections.get(&(cx, cy, cz + 1)).map(|s| s.light.as_slice()),
            ];

            if let Some(mesh) = mesh_section(
                cx,
                cy,
                cz,
                &center.ids,
                &center.light,
                &neighbors,
                &neighbor_lights,
            ) {
                result.stats.faces += mesh.faces;
                result.stats.quads += mesh.quads;
                result.stats.vertices += mesh.vertices.len() as u64;
                result.stats.indices += mesh.indices.len() as u64;

                let min = [(cx * 16) as f32, (cy * 16) as f32, (cz * 16) as f32];
                result.sections.push(SectionMeshData {
                    key,
                    vertices: mesh.vertices,
                    indices: mesh.indices,
                    opaque_count: mesh.opaque_count,
                    min,
                    max: [min[0] + 16.0, min[1] + 16.0, min[2] + 16.0],
                });
            }
        }
    });

    result
}

pub fn remesh(key: (i32, i32, i32)) -> Option<SectionMeshData> {
    let (cx, cy, cz) = key;
    let (center, center_light, neighbors, neighbor_lights) = SECTIONS.with(|cell| {
        let sections = cell.borrow();
        let center = sections.get(&key)?.ids.clone();
        let center_light = sections.get(&key)?.light.clone();
        let neighbors = [
            sections.get(&(cx - 1, cy, cz)).map(|s| s.ids.clone()),
            sections.get(&(cx + 1, cy, cz)).map(|s| s.ids.clone()),
            sections.get(&(cx, cy - 1, cz)).map(|s| s.ids.clone()),
            sections.get(&(cx, cy + 1, cz)).map(|s| s.ids.clone()),
            sections.get(&(cx, cy, cz - 1)).map(|s| s.ids.clone()),
            sections.get(&(cx, cy, cz + 1)).map(|s| s.ids.clone()),
        ];
        let neighbor_lights = [
            sections.get(&(cx - 1, cy, cz)).map(|s| s.light.clone()),
            sections.get(&(cx + 1, cy, cz)).map(|s| s.light.clone()),
            sections.get(&(cx, cy - 1, cz)).map(|s| s.light.clone()),
            sections.get(&(cx, cy + 1, cz)).map(|s| s.light.clone()),
            sections.get(&(cx, cy, cz - 1)).map(|s| s.light.clone()),
            sections.get(&(cx, cy, cz + 1)).map(|s| s.light.clone()),
        ];
        Some((center, center_light, neighbors, neighbor_lights))
    })?;

    let borrowed: [Option<&[i32]>; 6] = [
        neighbors[0].as_deref(),
        neighbors[1].as_deref(),
        neighbors[2].as_deref(),
        neighbors[3].as_deref(),
        neighbors[4].as_deref(),
        neighbors[5].as_deref(),
    ];
    let borrowed_light: [Option<&[u8]>; 6] = [
        neighbor_lights[0].as_deref(),
        neighbor_lights[1].as_deref(),
        neighbor_lights[2].as_deref(),
        neighbor_lights[3].as_deref(),
        neighbor_lights[4].as_deref(),
        neighbor_lights[5].as_deref(),
    ];

    let min = [(cx * 16) as f32, (cy * 16) as f32, (cz * 16) as f32];
    Some(
        match mesh_section(
            cx,
            cy,
            cz,
            &center,
            &center_light,
            &borrowed,
            &borrowed_light,
        ) {
            Some(mesh) => SectionMeshData {
                key,
                vertices: mesh.vertices,
                indices: mesh.indices,
                opaque_count: mesh.opaque_count,
                min,
                max: [min[0] + 16.0, min[1] + 16.0, min[2] + 16.0],
            },
            None => SectionMeshData {
                key,
                vertices: Vec::new(),
                indices: Vec::new(),
                opaque_count: 0,
                min,
                max: [min[0] + 16.0, min[1] + 16.0, min[2] + 16.0],
            },
        },
    )
}

/// Recompute the renderer-only BFS light field. Java's authoritative light is
/// never overwritten.
pub fn rebuild_light() {
    if JAVA_LIGHT.with(|flag| flag.get()) {
        return;
    }
    let snapshot = SECTIONS.with(|cell| {
        cell.borrow()
            .iter()
            .map(|(key, section)| (*key, section.ids.clone()))
            .collect::<HashMap<_, _>>()
    });
    if snapshot.is_empty() {
        return;
    }
    let light = lighting::compute(&snapshot);
    let keys: Vec<_> = light.keys().copied().collect();
    SECTIONS.with(|cell| {
        let mut sections = cell.borrow_mut();
        for (key, packed) in light {
            if let Some(section) = sections.get_mut(&key) {
                section.light = packed;
            }
        }
    });
    for key in keys {
        mark_dirty(key.0, key.1, key.2);
    }
    LIGHT_STALE.with(|flag| flag.set(false));
}

pub fn rebuild_light_if_stale() {
    if !LIGHT_STALE.with(|flag| flag.replace(false)) {
        return;
    }
    // Java owns light as well as blocks: do not run the renderer-only BFS over
    // a stream of Java sections (new light bytes arrive through
    // `receive_java_light` instead).
    if JAVA_CONNECTED.with(|flag| flag.get()) {
        return;
    }
    rebuild_light();
}

/// Renderer-only demo world: Horizon-derived noise terrain, biomes, trees and
/// boulders, centred on (0, 0). Java replaces every section when it connects.
pub fn generate_demo() {
    let generator = WorldGen::new(DEMO_SEED);
    SECTIONS.with(|cell| cell.borrow_mut().clear());
    PENDING_LIGHT.with(|cell| cell.borrow_mut().clear());
    DIRTY.with(|queue| queue.borrow_mut().clear());
    JAVA_CONNECTED.with(|flag| flag.set(false));
    JAVA_LIGHT.with(|flag| flag.set(false));

    for cz in -CHUNK_RADIUS..=CHUNK_RADIUS {
        for cx in -CHUNK_RADIUS..=CHUNK_RADIUS {
            for cy in 0..LAYERS {
                let ids = generator.section(cx, cy, cz);
                store(cx, cy, cz, 1, &ids);
            }
        }
    }
    generator.decorate(|x, y, z, block| put(x, y, z, block));
    rebuild_light();
}

fn put(x: i32, y: i32, z: i32, block: i32) {
    if y < 0 || y >= WORLD_TOP {
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

/// Renderer-only fallback spawn: pick a low grassy clearing near water so the
/// first view shows the same kind of plains/forest shoreline as the reference
/// screenshots, then look toward the nearest water.
pub fn demo_spawn() -> [f32; 3] {
    demo_spawn_pose().0
}

pub fn demo_spawn_pose() -> ([f32; 3], f32, f32) {
    const EYE_HEIGHT: f32 = 1.62;
    const TOP_Y: i32 = WORLD_TOP - 1;
    let (mut spawn_x, mut spawn_y, mut spawn_z) = (0, SEA_LEVEL + 2, 0);
    let mut yaw = 0.0f32;

    SECTIONS.with(|cell| {
        let sections = cell.borrow();
        let block_at = |x: i32, y: i32, z: i32| -> i32 {
            if y < 0 || y >= WORLD_TOP {
                return 0;
            }
            let key = (x >> 4, y >> 4, z >> 4);
            sections.get(&key).map_or(0, |section| {
                section.ids[block_index((x & 15) as usize, (y & 15) as usize, (z & 15) as usize)]
            })
        };
        let top_solid = |x: i32, z: i32| -> Option<i32> {
            let mut y = TOP_Y;
            while y >= 0 {
                let id = block_at(x, y, z);
                if crate::material::opacity(id) >= 15 {
                    return Some(y);
                }
                y -= 1;
            }
            None
        };

        let mut best = (f32::MIN, 0, 0, 0);
        for z in (-40..=40).step_by(2) {
            for x in (-40..=40).step_by(2) {
                let Some(ty) = top_solid(x, z) else {
                    continue;
                };
                let top = block_at(x, ty, z);
                let mut score = if top == crate::material::B_GRASS {
                    100.0
                } else {
                    -100.0
                };
                score -= ((ty - (SEA_LEVEL + 5)).abs() as f32) * 1.4;
                if ty > SEA_LEVEL + 20 {
                    score -= (ty - SEA_LEVEL - 20) as f32 * 4.0;
                }
                let mut water = 0i32;
                let mut trees = 0i32;
                for dz in (-32..=32).step_by(4) {
                    for dx in (-32..=32).step_by(4) {
                        if dx * dx + dz * dz > 32 * 32 {
                            continue;
                        }
                        let wx = x + dx;
                        let wz = z + dz;
                        let surface = block_at(wx, SEA_LEVEL, wz);
                        if surface == crate::material::B_WATER || surface == crate::material::B_ICE {
                            water += 1;
                        }
                        let canopy = block_at(wx, ty + 3, wz);
                        if canopy == crate::material::B_LEAVES || canopy == crate::material::B_LOG {
                            trees += 1;
                        }
                    }
                }
                score += water as f32 * 1.2 + trees.min(14) as f32 * 2.6;
                let mut local_grass = 0i32;
                let mut local_sand = 0i32;
                for dz in (-12..=12).step_by(2) {
                    for dx in (-12..=12).step_by(2) {
                        if let Some(uy) = top_solid(x + dx, z + dz) {
                            match block_at(x + dx, uy, z + dz) {
                                crate::material::B_GRASS => local_grass += 1,
                                crate::material::B_SAND => local_sand += 1,
                                _ => {}
                            }
                        }
                    }
                }
                score += local_grass as f32 * 1.1 - local_sand as f32 * 0.55;
                let distance = ((x * x + z * z) as f32).sqrt();
                score -= distance * 0.12;
                // Keep a clearing around the eye so the first view is not a
                // tree trunk filling the screen.
                for dz in -4..=4 {
                    for dx in -4..=4 {
                        for dy in 0..=4 {
                            if block_at(x + dx, ty + 1 + dy, z + dz) != 0 {
                                score -= 1200.0;
                            }
                        }
                    }
                }
                if score > best.0 {
                    best = (score, x, ty, z);
                }
            }
        }
        if best.0 == f32::MIN {
            if let Some(ty) = top_solid(0, 0) {
                best = (0.0, 0, ty, 0);
            }
        }
        let (_, x, ty, z) = best;
        spawn_x = x;
        spawn_y = ty + 1;
        spawn_z = z;

        let mut best_view = (f32::MIN, 0.0f32);
        for step in 0..16 {
            let angle = step as f32 * std::f32::consts::PI / 8.0;
            let dir = (-angle.sin(), -angle.cos());
            let mut view = 0.0f32;
            for d in [8.0f32, 14.0, 20.0, 28.0, 36.0] {
                let px = x + (dir.0 * d).round() as i32;
                let pz = z + (dir.1 * d).round() as i32;
                let surface = block_at(px, SEA_LEVEL, pz);
                if surface == crate::material::B_WATER || surface == crate::material::B_ICE {
                    view += 2.0;
                }
                if d >= 14.0 {
                    if let Some(uy) = top_solid(px, pz) {
                        match block_at(px, uy, pz) {
                            crate::material::B_GRASS => view += 1.0,
                            crate::material::B_LEAVES | crate::material::B_LOG => view += 2.5,
                            _ => {}
                        }
                    }
                }
            }
            if view > best_view.0 {
                best_view = (view, angle);
            }
        }
        yaw = best_view.1;

    });

    (
        [spawn_x as f32 + 0.5, spawn_y as f32 + EYE_HEIGHT, spawn_z as f32 + 0.5],
        yaw,
        -0.06,
    )
}

/// Solid query for the renderer-only walking fallback.
pub fn is_solid(x: i32, y: i32, z: i32) -> bool {
    if y < 0 || y >= WORLD_TOP {
        return y < 0;
    }
    let key = (x >> 4, y >> 4, z >> 4);
    SECTIONS.with(|cell| {
        let sections = cell.borrow();
        sections.get(&key).is_some_and(|section| {
            let id =
                section.ids[block_index((x & 15) as usize, (y & 15) as usize, (z & 15) as usize)];
            id != 0 && !crate::material::is_fluid(id) && !crate::material::is_cross(id)
        })
    })
}

/// Debug helper for the headless harness: ASCII biome/height map around spawn.
#[wasm_bindgen]
pub fn debug_world_map() -> String {
    let generator = WorldGen::new(DEMO_SEED);
    let mut out = String::new();
    for z in (-56..=56).step_by(2) {
        for x in (-56..=56).step_by(2) {
            let h = generator.height(x, z);
            let b = generator.biome(x, z, h);
            out.push(match b {
                crate::worldgen::Biome::Ocean => '~',
                crate::worldgen::Biome::Beach => '.',
                crate::worldgen::Biome::Plains => ',',
                crate::worldgen::Biome::Forest => 'T',
                crate::worldgen::Biome::Desert => 'd',
                crate::worldgen::Biome::Snowy => '*',
                crate::worldgen::Biome::Mountains => '^',
            });
        }
        out.push('\n');
    }
    out
}
