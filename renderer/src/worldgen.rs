//! Renderer-only demo world generation.
//!
//! The real client's world generation stays in Java (the Brain). This module is
//! the fallback used by the deployed renderer-only page and by the standalone
//! `body.html` harness, so the scene has real terrain instead of the old sine
//! island. The noise itself is Horizon's vanilla-compatible Perlin (`noise.rs`);
//! the climate/biome split below mirrors the shape of vanilla's multi-noise
//! source (continentalness + erosion + temperature + humidity) without trying to
//! replace the server's authoritative generator.

use crate::material::{
    B_ANDESITE, B_BEDROCK, B_CLAY, B_COAL_ORE, B_COPPER_ORE, B_DANDELION, B_DEEPSLATE,
    B_DEEPSLATE_COAL_ORE, B_DEEPSLATE_COPPER_ORE, B_DEEPSLATE_DIAMOND_ORE, B_DEEPSLATE_GOLD_ORE,
    B_DEEPSLATE_IRON_ORE, B_DEEPSLATE_LAPIS_ORE, B_DEEPSLATE_REDSTONE_ORE, B_DIAMOND_ORE,
    B_DIORITE, B_DIRT, B_EMERALD_ORE, B_GOLD_ORE, B_GRANITE, B_GRASS, B_GRAVEL, B_ICE, B_IRON_ORE,
    B_LAPIS_ORE, B_LEAVES, B_LOG, B_POPPY, B_REDSTONE_ORE, B_SAND, B_SANDSTONE, B_SHORT_GRASS,
    B_SNOW, B_STONE, B_TUFF, B_WATER,
};
use crate::noise::NoiseSet;

pub const CHUNK_RADIUS: i32 = 4; // 8x8 chunks, centred on (0, 0)
pub const LAYERS: i32 = 4; // 64 blocks tall
pub const SEA_LEVEL: i32 = 22;
pub const WORLD_BOTTOM: i32 = 0;
pub const WORLD_TOP: i32 = LAYERS * 16;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Biome {
    Ocean,
    Beach,
    Plains,
    Forest,
    Desert,
    Snowy,
    Mountains,
}

pub struct WorldGen {
    noise: NoiseSet,
    seed: i64,
}

impl WorldGen {
    pub fn new(seed: i64) -> Self {
        Self {
            noise: NoiseSet::new(seed),
            seed,
        }
    }

    /// Continentalness chooses land vs sea, erosion flattens or roughens it and
    /// the detail octave adds the small hills that make greedy meshing visible
    /// as real terrain rather than a plastic sheet.
    pub fn height(&self, x: i32, z: i32) -> i32 {
        let fx = f64::from(x);
        let fz = f64::from(z);
        let continent = self.noise.continentalness(fx, fz);
        let erosion = self.noise.erosion(fx, fz);
        let detail = self.noise.detail(fx, fz);

        let base = if continent < -0.18 {
            SEA_LEVEL as f32 - 10.0 + continent * 10.0
        } else {
            SEA_LEVEL as f32 - 2.0 + continent * 17.0
        };
        // Low erosion -> mountains, high erosion -> plains.
        let relief = (1.0 - erosion.clamp(-1.0, 1.0)) * 6.0;
        let mut height = base + relief + detail * 2.6 + 2.0;

        // A deterministic meandering river near x = 10 gives the first view a
        // shoreline like the reference screenshot.
        let center = 10.0 + (fz * 0.035).sin() * 7.0 + f64::from(self.noise.detail(fx * 0.12, fz * 0.12)) * 3.0;
        let distance = (fx - center).abs();
        let t = (distance / 60.0).clamp(0.0, 1.0);
        let river = 1.0 - t * t * (3.0 - 2.0 * t);
        let shore = SEA_LEVEL as f32 - 2.0 + distance as f32 * 0.22;
        height = height * (1.0 - river as f32) + height.min(shore) * river as f32;
        (height.round() as i32).clamp(3, WORLD_TOP - 6)
    }

    pub fn biome(&self, x: i32, z: i32, height: i32) -> Biome {
        let temperature = self.noise.temperature(f64::from(x), f64::from(z));
        let humidity = self.noise.humidity(f64::from(x), f64::from(z));
        if height < SEA_LEVEL - 1 {
            Biome::Ocean
        } else if height <= SEA_LEVEL + 1 {
            Biome::Beach
        } else if height > 52 {
            Biome::Mountains
        } else if temperature < -0.22 {
            Biome::Snowy
        } else if temperature > 0.28 && humidity < 0.05 {
            Biome::Desert
        } else if humidity > 0.12 {
            Biome::Forest
        } else {
            Biome::Plains
        }
    }

    fn top_block(&self, biome: Biome, height: i32) -> i32 {
        match biome {
            Biome::Ocean | Biome::Beach => B_SAND,
            Biome::Desert => B_SAND,
            Biome::Snowy => B_SNOW,
            Biome::Mountains if height > 58 => B_SNOW,
            Biome::Mountains => B_GRASS,
            Biome::Plains | Biome::Forest => B_GRASS,
        }
    }

    fn filler_block(&self, biome: Biome, depth: i32) -> i32 {
        match biome {
            Biome::Ocean | Biome::Beach | Biome::Desert if depth <= 2 => B_SAND,
            Biome::Ocean | Biome::Beach | Biome::Desert => B_SANDSTONE,
            Biome::Snowy if depth <= 2 => B_DIRT,
            Biome::Mountains => B_DIRT,
            _ => B_DIRT,
        }
    }

    /// Fills one 16^3 section. Section coordinates are world section indices.
    pub fn section(&self, cx: i32, cy: i32, cz: i32) -> Vec<i32> {
        let mut ids = vec![0i32; 4096];
        let ox = cx * 16;
        let oy = cy * 16;
        let oz = cz * 16;
        // Heights/biomes are column properties: compute them once per section
        // instead of once per block (4096 noise evaluations otherwise).
        let mut heights = [[0i32; 16]; 16];
        let mut biomes = [[Biome::Plains; 16]; 16];
        for z in 0..16usize {
            let world_z = oz + z as i32;
            for x in 0..16usize {
                let world_x = ox + x as i32;
                let height = self.height(world_x, world_z);
                heights[z][x] = height;
                biomes[z][x] = self.biome(world_x, world_z, height);
            }
        }
        for y in 0..16usize {
            let world_y = oy + y as i32;
            for z in 0..16usize {
                let world_z = oz + z as i32;
                for x in 0..16usize {
                    let world_x = ox + x as i32;
                    let height = heights[z][x];
                    if world_y <= 0 {
                        ids[y * 256 + z * 16 + x] = B_BEDROCK;
                        continue;
                    }
                    if world_y >= height {
                        if world_y <= SEA_LEVEL {
                            let biome = biomes[z][x];
                            ids[y * 256 + z * 16 + x] = if biome == Biome::Snowy
                                && world_y == SEA_LEVEL
                            {
                                B_ICE
                            } else {
                                B_WATER
                            };
                        }
                        continue;
                    }
                    let biome = biomes[z][x];
                    let depth = height - 1 - world_y;
                    let id = if depth == 0 {
                        self.top_block(biome, height)
                    } else if depth <= 3 {
                        self.filler_block(biome, depth)
                    } else {
                        self.ore_or_stone(world_x, world_y, world_z, depth, height)
                    };
                    ids[y * 256 + z * 16 + x] = id;
                }
            }
        }
        ids
    }

    fn ore_or_stone(&self, x: i32, y: i32, z: i32, depth: i32, height: i32) -> i32 {
        let h = hash3(x, y, z) ^ self.seed as u32;
        let deep = y < 10;
        // Vanilla-like stone variants so exposed cliffs are not one flat grey.
        let base = if deep {
            B_DEEPSLATE
        } else {
            match h & 15 {
                0 => B_ANDESITE,
                1 => B_DIORITE,
                2 => B_GRANITE,
                3 | 4 if y < 28 => B_TUFF,
                _ => B_STONE,
            }
        };
        if depth > 5 && y < height - 2 {
            let ore = if deep {
                match h % 211 {
                    0 => B_DEEPSLATE_COAL_ORE,
                    1 => B_DEEPSLATE_IRON_ORE,
                    2 => B_DEEPSLATE_COPPER_ORE,
                    3 if y < 24 => B_DEEPSLATE_GOLD_ORE,
                    4 if y < 24 => B_DEEPSLATE_REDSTONE_ORE,
                    5 if y < 20 => B_DEEPSLATE_LAPIS_ORE,
                    6 if y < 16 => B_DEEPSLATE_DIAMOND_ORE,
                    _ => base,
                }
            } else {
                match h % 233 {
                    0 => B_COAL_ORE,
                    1 => B_IRON_ORE,
                    2 => B_COPPER_ORE,
                    3 if y < 28 => B_GOLD_ORE,
                    4 if y < 26 => B_REDSTONE_ORE,
                    5 if y < 24 => B_LAPIS_ORE,
                    6 if y < 20 => B_DIAMOND_ORE,
                    7 if y < 24 => B_EMERALD_ORE,
                    _ => base,
                }
            };
            if ore != base {
                return ore;
            }
        }
        if depth > 2 && h % 197 == 0 {
            return B_GRAVEL;
        }
        if depth > 2 && h % 263 == 0 {
            return B_CLAY;
        }
        base
    }

    /// Oak/plains/forest, spruce-ish snowy and desert boulders. `put` writes a
    /// block into whichever section owns it, so a tree may cross a chunk edge.
    pub fn decorate(&self, mut put: impl FnMut(i32, i32, i32, i32)) {
        let min = -CHUNK_RADIUS * 16;
        let max = (CHUNK_RADIUS + 1) * 16 - 1;
        // Ground cover first; trees below overwrite the cells they occupy.
        for cz in -CHUNK_RADIUS..=CHUNK_RADIUS {
            for cx in -CHUNK_RADIUS..=CHUNK_RADIUS {
                let ox = cx * 16;
                let oz = cz * 16;
                for z in 0..16 {
                    for x in 0..16 {
                        let wx = ox + x;
                        let wz = oz + z;
                        let ground = self.height(wx, wz);
                        if ground <= SEA_LEVEL + 1 {
                            continue;
                        }
                        if self.top_block(self.biome(wx, wz, ground), ground) != B_GRASS {
                            continue;
                        }
                        let h = hash3(wx, 11, wz) ^ self.seed as u32;
                        let roll = h % 100;
                        let plant = if roll < 30 {
                            B_SHORT_GRASS
                        } else if roll < 32 {
                            B_POPPY
                        } else if roll < 34 {
                            B_DANDELION
                        } else {
                            continue;
                        };
                        put(wx, ground, wz, plant);
                    }
                }
            }
        }
        for cz in -CHUNK_RADIUS..=CHUNK_RADIUS {
            for cx in -CHUNK_RADIUS..=CHUNK_RADIUS {
                let ox = cx * 16;
                let oz = cz * 16;
                let center_x = ox + 8;
                let center_z = oz + 8;
                let height = self.height(center_x, center_z);
                let biome = self.biome(center_x, center_z, height);
                let density = self
                    .noise
                    .tree_density(f64::from(center_x), f64::from(center_z));
                let attempts = match biome {
                    Biome::Forest => 1,
                    Biome::Plains => 1,
                    Biome::Snowy => 1,
                    Biome::Desert => 1,
                    Biome::Mountains => 1,
                    Biome::Beach | Biome::Ocean => 0,
                };
                for i in 0..attempts {
                    let h = hash3(center_x, i, center_z) ^ self.seed as u32;
                    let x = ox + 2 + (h % 12) as i32;
                    let z = oz + 2 + ((h >> 8) % 12) as i32;
                    if x < min || x > max || z < min || z > max {
                        continue;
                    }
                    let ground = self.height(x, z);
                    let site = self.biome(x, z, ground);
                    if site != biome {
                        continue;
                    }
                    match biome {
                        Biome::Desert => {
                            if density > -0.2 {
                                boulder(&mut put, x, ground, z, h);
                            }
                        }
                        Biome::Snowy => {
                            if density > 0.0 {
                                spruce(&mut put, x, ground, z, h);
                            }
                        }
                        Biome::Forest => {
                            if density > -0.18 {
                                oak(&mut put, x, ground, z, h);
                            }
                        }
                        Biome::Plains | Biome::Mountains => {
                            if density > 0.22 {
                                oak(&mut put, x, ground, z, h);
                            }
                        }
                        Biome::Beach | Biome::Ocean => {}
                    }
                }
            }
        }
    }
}

fn oak(put: &mut impl FnMut(i32, i32, i32, i32), x: i32, ground: i32, z: i32, h: u32) {
    let trunk = 4 + ((h >> 16) % 3) as i32;
    for dy in 0..trunk {
        put(x, ground + dy, z, B_LOG);
    }
    for dy in 0..3 {
        let radius: i32 = if dy == 2 { 1 } else { 2 };
        for dz in -radius..=radius {
            for dx in -radius..=radius {
                if dx.abs() == radius && dz.abs() == radius && dy == 2 {
                    continue;
                }
                put(x + dx, ground + trunk - 1 + dy, z + dz, B_LEAVES);
            }
        }
    }
}

fn spruce(put: &mut impl FnMut(i32, i32, i32, i32), x: i32, ground: i32, z: i32, h: u32) {
    let trunk = 6 + ((h >> 16) % 3) as i32;
    for dy in 0..trunk {
        put(x, ground + dy, z, B_LOG);
    }
    for dy in 0..trunk - 2 {
        let radius: i32 = if dy % 2 == 0 { 1 } else { 0 };
        for dz in -radius..=radius {
            for dx in -radius..=radius {
                if dx == 0 && dz == 0 {
                    continue;
                }
                put(x + dx, ground + 2 + dy, z + dz, B_LEAVES);
            }
        }
    }
    put(x, ground + trunk, z, B_LEAVES);
}

fn boulder(put: &mut impl FnMut(i32, i32, i32, i32), x: i32, ground: i32, z: i32, h: u32) {
    let width = 1 + ((h >> 16) % 2) as i32;
    for dy in 0..2 {
        for dz in -width..=width {
            for dx in -width..=width {
                if dx.abs() + dz.abs() + dy > width + 1 {
                    continue;
                }
                put(x + dx, ground + dy, z + dz, B_SANDSTONE);
            }
        }
    }
}

fn hash3(x: i32, y: i32, z: i32) -> u32 {
    let mut h = (x as u32).wrapping_mul(0x9E37_79B1)
        ^ (y as u32).wrapping_mul(0x85EB_CA77)
        ^ (z as u32).wrapping_mul(0xC2B2_AE3D);
    h ^= h >> 15;
    h = h.wrapping_mul(0x2545_F491);
    h ^= h >> 13;
    h
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn print_maps() {
        for seed in [0x5eedi64, 0, 1, 2, 3, 4, 5, 42] {
            let world = WorldGen::new(seed);
            let mut water = 0;
            println!("--- seed {seed}");
            for z in (-56..=56).step_by(2) {
                let mut line = String::new();
                for x in (-56..=56).step_by(2) {
                    let h = world.height(x, z);
                    let b = world.biome(x, z, h);
                    if h <= SEA_LEVEL {
                        water += 1;
                    }
                    line.push(match b {
                        Biome::Ocean => '~',
                        Biome::Beach => '.',
                        Biome::Plains => ',',
                        Biome::Forest => 'T',
                        Biome::Desert => 'd',
                        Biome::Snowy => '*',
                        Biome::Mountains => '^',
                    });
                }
                println!("{line}");
            }
            println!("water {water}");
        }
    }

    #[test]
    fn terrain_has_relief_and_valid_blocks() {
        let world = WorldGen::new(0);
        let mut min = i32::MAX;
        let mut max = i32::MIN;
        for x in -128..128 {
            for z in -128..128 {
                let h = world.height(x, z);
                min = min.min(h);
                max = max.max(h);
                assert!((3..=WORLD_TOP - 6).contains(&h));
            }
        }
        assert!(
            max - min > 12,
            "terrain should not be a flat plane: {min}..{max}"
        );
    }

    #[test]
    fn centered_spawn_column_is_solid() {
        let world = WorldGen::new(0);
        let h = world.height(0, 0);
        assert!(h > SEA_LEVEL - 1);
        let surface = world.top_block(world.biome(0, 0, h), h);
        assert!(surface != 0);
    }
}
