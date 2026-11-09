//! Minecraft-style light propagation for the renderer-only world.
//!
//! Horizon's `mesher/lighting.rs` is the source of the face-light and
//! ambient-occlusion rules used in `mesh.rs`. This module supplies the two
//! 0..=15 fields those rules sample:
//!
//! * skylight starts at 15 at every column open to the sky and loses one level
//!   when it travels sideways/up, while travelling straight down through air
//!   keeps its level (vanilla's column rule);
//! * block light starts at the emitting block's `getLightEmission` and loses
//!   one level per block in every direction.
//!
//! Opaque blocks consume all light (`getLightBlock = 15`); leaves/glass use the
//! material table's smaller opacity. Each cell is packed as
//! `sky << 4 | block` so the mesher and the WGSL lightmap consume one byte.

use std::collections::{HashMap, VecDeque};

use crate::material::{emission, opacity};

pub type SectionKey = (i32, i32, i32);
pub type SectionIds = HashMap<SectionKey, Vec<i32>>;
pub type LightMap = HashMap<SectionKey, Vec<u8>>;

/// Above this many cells a single global flood fill would block the frame; the
/// renderer then keeps the last light field and new sections use full skylight.
const MAX_VOLUME: usize = 8_000_000;

pub fn compute(sections: &SectionIds) -> LightMap {
    if sections.is_empty() {
        return HashMap::new();
    }

    let mut min = [i32::MAX; 3];
    let mut max = [i32::MIN; 3];
    for &(cx, cy, cz) in sections.keys() {
        min[0] = min[0].min(cx * 16);
        min[1] = min[1].min(cy * 16);
        min[2] = min[2].min(cz * 16);
        max[0] = max[0].max(cx * 16 + 15);
        max[1] = max[1].max(cy * 16 + 15);
        max[2] = max[2].max(cz * 16 + 15);
    }

    let sx = (max[0] - min[0] + 1) as usize;
    let sy = (max[1] - min[1] + 1) as usize;
    let sz = (max[2] - min[2] + 1) as usize;
    let volume = sx.saturating_mul(sy).saturating_mul(sz);
    if volume == 0 || volume > MAX_VOLUME {
        return HashMap::new();
    }

    let index = |x: usize, y: usize, z: usize| x + sx * (z + sz * y);
    let mut opac = vec![0u8; volume];
    let mut emit = vec![0u8; volume];
    for (&(cx, cy, cz), ids) in sections {
        let ox = (cx * 16 - min[0]) as usize;
        let oy = (cy * 16 - min[1]) as usize;
        let oz = (cz * 16 - min[2]) as usize;
        for y in 0..16usize {
            for z in 0..16usize {
                for x in 0..16usize {
                    let id = ids[y * 256 + z * 16 + x];
                    let i = index(ox + x, oy + y, oz + z);
                    opac[i] = opacity(id);
                    emit[i] = emission(id);
                }
            }
        }
    }

    let mut sky = vec![0u8; volume];
    let mut queue = VecDeque::new();

    // Column pass: everything above the first opaque block sees the sky.
    for z in 0..sz {
        for x in 0..sx {
            let mut covered = false;
            for y in (0..sy).rev() {
                let i = index(x, y, z);
                if !covered && opac[i] == 0 {
                    sky[i] = 15;
                    queue.push_back(i);
                }
                // Any non-air block interrupts a direct sky column. Leaves
                // still let light through via the BFS below (minus one level),
                // but the air directly under a canopy must not be seeded 15.
                if opac[i] > 0 {
                    covered = true;
                }
            }
        }
    }

    propagate(&mut sky, &mut queue, &opac, sx, sy, sz, true);

    let mut block = vec![0u8; volume];
    for i in 0..volume {
        if emit[i] > 0 {
            block[i] = emit[i];
            queue.push_back(i);
        }
    }
    propagate(&mut block, &mut queue, &opac, sx, sy, sz, false);

    // Regroup the flat field into one 4096-byte packed map per section.
    let mut out = HashMap::with_capacity(sections.len());
    for &(cx, cy, cz) in sections.keys() {
        let ox = (cx * 16 - min[0]) as usize;
        let oy = (cy * 16 - min[1]) as usize;
        let oz = (cz * 16 - min[2]) as usize;
        let mut packed = vec![0u8; 4096];
        for y in 0..16usize {
            for z in 0..16usize {
                for x in 0..16usize {
                    let i = index(ox + x, oy + y, oz + z);
                    packed[y * 256 + z * 16 + x] = (sky[i] << 4) | block[i];
                }
            }
        }
        out.insert((cx, cy, cz), packed);
    }
    out
}

fn propagate(
    field: &mut [u8],
    queue: &mut VecDeque<usize>,
    opac: &[u8],
    sx: usize,
    sy: usize,
    sz: usize,
    sky: bool,
) {
    let index = |x: usize, y: usize, z: usize| x + sx * (z + sz * y);
    while let Some(i) = queue.pop_front() {
        let level = field[i];
        if level == 0 {
            continue;
        }
        let x = i % sx;
        let z = (i / sx) % sz;
        let y = i / (sx * sz);
        let mut visit = |nx: usize, ny: usize, nz: usize, down: bool| {
            let j = index(nx, ny, nz);
            let target = opac[j];
            if target >= 15 {
                return;
            }
            let step = target.max(1);
            let next = if sky && down && target == 0 {
                level
            } else {
                level.saturating_sub(step)
            };
            if next > field[j] {
                field[j] = next;
                queue.push_back(j);
            }
        };
        if x > 0 {
            visit(x - 1, y, z, false);
        }
        if x + 1 < sx {
            visit(x + 1, y, z, false);
        }
        if z > 0 {
            visit(x, y, z - 1, false);
        }
        if z + 1 < sz {
            visit(x, y, z + 1, false);
        }
        if y + 1 < sy {
            visit(x, y + 1, z, false);
        }
        if y > 0 {
            visit(x, y - 1, z, true);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn section_with(ids: Vec<i32>) -> SectionIds {
        let mut map = HashMap::new();
        map.insert((0, 0, 0), ids);
        map
    }

    #[test]
    fn open_column_is_full_skylight() {
        let light = compute(&section_with(vec![0; 4096]));
        // Top cell is sky 15; a block in the middle of the open column also
        // keeps 15 because skylight travels straight down without loss.
        assert_eq!(light[&(0, 0, 0)][15 * 256], 15 << 4);
    }

    #[test]
    fn opaque_roof_shadows_the_cells_below() {
        let mut ids = vec![0; 4096];
        // Solid slab at y = 8.
        for z in 0..16 {
            for x in 0..16 {
                ids[8 * 256 + z * 16 + x] = crate::material::B_STONE;
            }
        }
        let light = compute(&section_with(ids));
        let packed = &light[&(0, 0, 0)];
        assert_eq!(packed[15 * 256], 15 << 4, "above the roof still sees sky");
        assert_eq!(packed[0], 0, "the sealed air cell below has no sky");
    }

    #[test]
    fn glowstone_lights_neighbours() {
        let mut ids = vec![0; 4096];
        ids[8 * 256 + 8 * 16 + 8] = crate::material::B_GLOWSTONE;
        let light = compute(&section_with(ids));
        let packed = &light[&(0, 0, 0)];
        let neighbour = packed[8 * 256 + 8 * 16 + 9] & 15;
        assert_eq!(neighbour, 14);
    }
}
