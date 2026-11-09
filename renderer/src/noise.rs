//! Vanilla-compatible gradient noise.
//!
//! Ported from Horizon (`crates/horizon-core/src/noise/{mod,perlin}.rs` and
//! `crates/horizon-core/src/random.rs`), MIT licensed, so the terrain keeps the
//! same permutation-table construction, coordinate wrapping and interpolation
//! order as Minecraft's `PerlinNoise`. This is deliberately a *copy* of the
//! proven algorithm rather than a new noise implementation: the demo world must
//! stop looking like flat sine waves and must be reproducible from a seed.

#[derive(Clone)]
struct LegacyRandom {
    state: u64,
}

impl LegacyRandom {
    const MULTIPLIER: u64 = 0x5_deec_e66d;
    const MASK: u64 = (1 << 48) - 1;

    fn new(seed: i64) -> Self {
        Self {
            state: (seed as u64 ^ Self::MULTIPLIER) & Self::MASK,
        }
    }

    fn bits(&mut self, count: u32) -> i32 {
        self.state = self.state.wrapping_mul(Self::MULTIPLIER).wrapping_add(11) & Self::MASK;
        (self.state >> (48 - count)) as i32
    }

    fn next_double(&mut self) -> f64 {
        let value = (i64::from(self.bits(26)) << 27) + i64::from(self.bits(27));
        value as f64 * (1.0 / 9_007_199_254_740_992.0)
    }

    fn next_int_bound(&mut self, bound: i32) -> i32 {
        debug_assert!(bound > 0);
        if bound & (bound - 1) == 0 {
            return ((i64::from(bound) * i64::from(self.bits(31))) >> 31) as i32;
        }
        loop {
            let bits = self.bits(31);
            let value = bits % bound;
            if bits.wrapping_sub(value).wrapping_add(bound - 1) >= 0 {
                return value;
            }
        }
    }
}

#[derive(Clone)]
struct GradientTable {
    offsets: [f64; 3],
    permutations: [u8; 256],
}

impl GradientTable {
    fn new(random: &mut LegacyRandom, offset_scale: f64) -> Self {
        let offsets = std::array::from_fn(|_| random.next_double() * offset_scale);
        let mut permutations: [u8; 256] = std::array::from_fn(|i| i as u8);
        for i in 0..256 {
            let other = i + random.next_int_bound((256 - i) as i32) as usize;
            permutations.swap(i, other);
        }
        Self {
            offsets,
            permutations,
        }
    }

    fn permute(&self, index: i32) -> i32 {
        i32::from(self.permutations[(index & 255) as usize])
    }

    fn gradient(&self, index: i32) -> [f32; 3] {
        PERLIN_GRADIENTS[(self.permute(index) & 15) as usize]
    }
}

// Perlin's 16-direction table (four directions are repeated).
const GRADIENTS: [[i32; 3]; 16] = [
    [1, 1, 0],
    [-1, 1, 0],
    [1, -1, 0],
    [-1, -1, 0],
    [1, 0, 1],
    [-1, 0, 1],
    [1, 0, -1],
    [-1, 0, -1],
    [0, 1, 1],
    [0, -1, 1],
    [0, 1, -1],
    [0, -1, -1],
    [1, 1, 0],
    [0, -1, 1],
    [-1, 1, 0],
    [0, -1, -1],
];

const PERLIN_GRADIENTS: [[f32; 3]; 16] = {
    let mut out = [[0.0; 3]; 16];
    let mut i = 0;
    while i < 16 {
        out[i] = [
            GRADIENTS[i][0] as f32,
            GRADIENTS[i][1] as f32,
            GRADIENTS[i][2] as f32,
        ];
        i += 1;
    }
    out
};

fn dot(gradient: [f32; 3], point: [f32; 3]) -> f32 {
    gradient[0] * point[0] + gradient[1] * point[1] + gradient[2] * point[2]
}

/// Vanilla's periodic coordinate reduction with the normal Far Lands flag off.
fn wrap(value: f64) -> f64 {
    let half = f64::from_bits(16_777_216.0_f64.to_bits() - 1);
    if value >= -half && value < half {
        value
    } else {
        value - (value / 33_554_432.0 + 0.5).floor() * 33_554_432.0
    }
}

fn floor_i32(value: f64) -> i32 {
    // Mth.floor: Java's floor is towards negative infinity, and the value is
    // already bounded by `wrap`, so the cast is exact for these coordinates.
    value.floor() as i32
}

fn smoothstep(value: f32) -> f32 {
    value * value * value * (value * (value * 6.0 - 15.0) + 10.0)
}

fn lerp(delta: f32, start: f32, end: f32) -> f32 {
    start + delta * (end - start)
}

fn lerp2(a: f32, b: f32, values: [f32; 4]) -> f32 {
    lerp(
        b,
        lerp(a, values[0], values[1]),
        lerp(a, values[2], values[3]),
    )
}

fn lerp3(fade: [f32; 3], values: [f32; 8]) -> f32 {
    lerp(
        fade[2],
        lerp2(
            fade[0],
            fade[1],
            [values[0], values[1], values[2], values[3]],
        ),
        lerp2(
            fade[0],
            fade[1],
            [values[4], values[5], values[6], values[7]],
        ),
    )
}

/// Horizon's `PerlinNoise`: vanilla's improved Perlin noise, same table and
/// interpolation order.
#[derive(Clone)]
struct PerlinNoise {
    table: GradientTable,
}

impl PerlinNoise {
    fn new(random: &mut LegacyRandom) -> Self {
        Self {
            table: GradientTable::new(random, 256.0),
        }
    }

    pub fn get2(&self, x: f64, z: f64) -> f32 {
        self.get3(wrap(x), 0.0, wrap(z))
    }

    pub fn get3(&self, x: f64, y: f64, z: f64) -> f32 {
        let (cell, relative) = self.coordinates([x, y, z]);
        let point = relative.map(|v| v as f32);
        self.sample(cell, point, point[1])
    }

    fn coordinates(&self, point: [f64; 3]) -> ([i32; 3], [f64; 3]) {
        let shifted: [f64; 3] = std::array::from_fn(|i| wrap(point[i]) + self.table.offsets[i]);
        let cell = shifted.map(floor_i32);
        (
            cell,
            std::array::from_fn(|i| shifted[i] - f64::from(cell[i])),
        )
    }

    fn gradients(&self, cell: [i32; 3]) -> [[f32; 3]; 8] {
        self.gradients_at(
            self.table.permute(cell[0]),
            self.table.permute(cell[0].wrapping_add(1)),
            cell[1],
            cell[2],
        )
    }

    fn gradients_at(&self, p: i32, q: i32, cell_y: i32, cell_z: i32) -> [[f32; 3]; 8] {
        let lower = self.table.permute(p.wrapping_add(cell_y));
        let upper = self.table.permute(p.wrapping_add(cell_y).wrapping_add(1));
        let lower_x = self.table.permute(q.wrapping_add(cell_y));
        let upper_x = self.table.permute(q.wrapping_add(cell_y).wrapping_add(1));
        let rows = [
            lower, lower_x, upper, upper_x, lower, lower_x, upper, upper_x,
        ];
        std::array::from_fn(|i| {
            self.table
                .gradient(rows[i].wrapping_add(cell_z).wrapping_add((i / 4) as i32))
        })
    }

    fn corner_values(gradients: [[f32; 3]; 8], point: [f32; 3]) -> [f32; 8] {
        std::array::from_fn(|i| {
            dot(
                gradients[i],
                [
                    point[0] - (i & 1) as f32,
                    point[1] - ((i >> 1) & 1) as f32,
                    point[2] - ((i >> 2) & 1) as f32,
                ],
            )
        })
    }

    fn sample(&self, cell: [i32; 3], point: [f32; 3], interpolation_y: f32) -> f32 {
        let values = Self::corner_values(self.gradients(cell), point);
        lerp3(
            [
                smoothstep(point[0]),
                smoothstep(interpolation_y),
                smoothstep(point[2]),
            ],
            values,
        )
    }
}

/// A deterministic perlin noise set, exactly the layout the demo worldgen needs:
/// one stream, five independent tables created in a fixed order.
pub struct NoiseSet {
    continent: PerlinNoise,
    erosion: PerlinNoise,
    detail: PerlinNoise,
    temperature: PerlinNoise,
    humidity: PerlinNoise,
    tree: PerlinNoise,
}

impl NoiseSet {
    pub fn new(seed: i64) -> Self {
        let mut random = LegacyRandom::new(seed);
        Self {
            continent: PerlinNoise::new(&mut random),
            erosion: PerlinNoise::new(&mut random),
            detail: PerlinNoise::new(&mut random),
            temperature: PerlinNoise::new(&mut random),
            humidity: PerlinNoise::new(&mut random),
            tree: PerlinNoise::new(&mut random),
        }
    }

    /// Fractal Brownian motion. The caller supplies the base frequency so each
    /// field can use the vanilla-like scale it needs.
    fn fbm(noise: &PerlinNoise, x: f64, z: f64, frequency: f64, octaves: u32) -> f32 {
        let mut amplitude = 1.0f32;
        let mut total = 0.0f32;
        let mut norm = 0.0f32;
        let mut f = frequency;
        for _ in 0..octaves {
            total += noise.get2(x * f, z * f) * amplitude;
            norm += amplitude;
            amplitude *= 0.5;
            f *= 2.0;
        }
        total / norm.max(f32::EPSILON)
    }

    pub fn continentalness(&self, x: f64, z: f64) -> f32 {
        Self::fbm(&self.continent, x, z, 0.0018, 5)
    }

    pub fn erosion(&self, x: f64, z: f64) -> f32 {
        Self::fbm(&self.erosion, x, z, 0.0065, 4)
    }

    pub fn detail(&self, x: f64, z: f64) -> f32 {
        Self::fbm(&self.detail, x, z, 0.035, 3)
    }

    pub fn temperature(&self, x: f64, z: f64) -> f32 {
        Self::fbm(&self.temperature, x, z, 0.0024, 3)
    }

    pub fn humidity(&self, x: f64, z: f64) -> f32 {
        Self::fbm(&self.humidity, x, z, 0.0031, 3)
    }

    pub fn tree_density(&self, x: f64, z: f64) -> f32 {
        Self::fbm(&self.tree, x, z, 0.045, 2)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn noise_is_deterministic() {
        let a = NoiseSet::new(0);
        let b = NoiseSet::new(0);
        assert_eq!(a.continentalness(12.5, -7.0), b.continentalness(12.5, -7.0));
        let c = NoiseSet::new(1);
        assert_ne!(a.continentalness(12.5, -7.0), c.continentalness(12.5, -7.0));
    }

    #[test]
    fn perlin_range_is_bounded() {
        let noise = NoiseSet::new(42);
        for i in 0..512 {
            let x = i as f64 * 3.17;
            let z = i as f64 * -1.91;
            let value = noise.continentalness(x, z);
            assert!(value.is_finite());
            assert!(value.abs() < 2.0);
        }
    }
}
