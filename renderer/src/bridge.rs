//! Bridge v0: the Java Brain writes 16^3 block-ID sections here.
//!
//! `upload_section(cx, cy, cz, version, ids)` is the wasm-bindgen export the
//! Brain calls. The store starts empty: the Body never invents terrain, so the
//! first pixels come from the real Java world.

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, VecDeque};

use wasm_bindgen::prelude::*;

use crate::lighting;
use crate::mesh::{block_index, mesh_section, Vertex, VOLUME};

/// World height used only to bound the renderer-only collision query. Java
/// owns the real build height; this is not a terrain generator.
const WORLD_TOP: i32 = 320;

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
    /// Set when the Brain takes ownership of the world. The renderer clears any
    /// stale arena once, before the first real section is drawn.
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
/// The first real chunk clears the empty/stale Body store. `cy` is implicitly
/// 0; worlds with a non-zero minimum Y use `receive_java_section` instead.
#[wasm_bindgen]
pub fn receive_java_chunk(chunk_x: i32, chunk_z: i32, block_ids: &[i32]) -> bool {
    receive_java_section(chunk_x, 0, chunk_z, block_ids)
}

/// Section-level form used by the full Java capture hook (16³ IDs plus cy).
/// Whether at least one valid section has connected the real Java client to this Body.
#[wasm_bindgen]
pub fn java_connected() -> bool {
    JAVA_CONNECTED.with(|flag| flag.get())
}

/// Number of real Java sections currently stored by the Rust Body.
#[wasm_bindgen]
pub fn java_section_count() -> u32 {
    SECTIONS.with(|cell| cell.borrow().len() as u32)
}

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
        // First real section: drop anything left over from a previous world.
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

/// Solid query for the standalone Body's debug walking fallback. It only sees
/// real Java sections; an empty store means every block is air.
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
