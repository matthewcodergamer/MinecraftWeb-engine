//! 16^3 greedy mesher with 18^3 padded neighbour assembly.
//!
//! Pure CPU meshing: no GPU device calls live here, so this can move into a
//! Worker later. Block IDs are opaque integers owned by the Java Brain; the
//! material table supplies the texture layer, full-cube opacity and emission.
//!
//! Lighting follows Horizon's `mesher/lighting.rs` (`BlockModelLighter` /
//! `ModelBlockRenderer`): every face samples the four light cells around each
//! corner, averages them with the centre weighted twice, and combines that with
//! ambient occlusion from the side/corner blocks. Greedy merging only merges
//! faces whose block *and* four light/AO corners are identical, so a light
//! gradient never gets flattened into one flat-shaded quad.

pub const DIM: usize = 16;
pub const VOLUME: usize = DIM * DIM * DIM;
const PAD: usize = DIM + 2;
const PAD_AREA: usize = PAD * PAD;
const PAD_VOLUME: usize = PAD * PAD * PAD;

/// Bridge index: `y*256 + z*16 + x` (y up), matching the documented layout.
pub fn block_index(x: usize, y: usize, z: usize) -> usize {
    y * DIM * DIM + z * DIM + x
}

fn pad_index(x: i32, y: i32, z: i32) -> usize {
    debug_assert!((-1..=DIM as i32).contains(&x));
    debug_assert!((-1..=DIM as i32).contains(&y));
    debug_assert!((-1..=DIM as i32).contains(&z));
    ((z + 1) as usize) * PAD_AREA + ((y + 1) as usize) * PAD + ((x + 1) as usize)
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct Vertex {
    pub position: [f32; 3],
    /// Tile-local UV in blocks. The sampler repeats the tile once per block, so a
    /// greedily merged quad keeps the vanilla texel density.
    pub uv: [f32; 2],
    /// Layer in the block texture array (`textures::layer_for`).
    pub layer: u32,
    /// Vanilla directional face shading (top 1.0, bottom 0.5, N/S 0.8, E/W 0.6).
    pub shade: u32,
    /// Packed light for this vertex: `sky << 4 | block`, both 0..=15.
    /// Stored as f32 so the rasteriser interpolates it smoothly; integer varyings
    /// would force flat interpolation under WGSL rules.
    pub light: f32,
    /// Ambient occlusion 0..=255 (255 = fully open).
    pub ao: u32,
}

impl Vertex {
    const ATTRS: [wgpu::VertexAttribute; 6] = [
        wgpu::VertexAttribute {
            format: wgpu::VertexFormat::Float32x3,
            offset: 0,
            shader_location: 0,
        },
        wgpu::VertexAttribute {
            format: wgpu::VertexFormat::Float32x2,
            offset: 12,
            shader_location: 1,
        },
        wgpu::VertexAttribute {
            format: wgpu::VertexFormat::Uint32,
            offset: 20,
            shader_location: 2,
        },
        wgpu::VertexAttribute {
            format: wgpu::VertexFormat::Uint32,
            offset: 24,
            shader_location: 3,
        },
        wgpu::VertexAttribute {
            format: wgpu::VertexFormat::Float32,
            offset: 28,
            shader_location: 4,
        },
        wgpu::VertexAttribute {
            format: wgpu::VertexFormat::Uint32,
            offset: 32,
            shader_location: 5,
        },
    ];

    pub fn layout() -> wgpu::VertexBufferLayout<'static> {
        wgpu::VertexBufferLayout {
            array_stride: std::mem::size_of::<Vertex>() as u64,
            step_mode: wgpu::VertexStepMode::Vertex,
            attributes: &Self::ATTRS,
        }
    }
}

pub struct SectionMesh {
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
    /// Visible block faces before merging (one quad each).
    pub faces: u64,
    /// Quads actually emitted after greedy merging.
    pub quads: u64,
}

#[derive(Clone, Copy, PartialEq, Eq)]
struct FaceCell {
    block: i32,
    /// Four corners in `(u0,v0), (u1,v0), (u1,v1), (u0,v1)` order.
    light: [u8; 4],
    ao: [u8; 4],
}

impl FaceCell {
    const EMPTY: Self = Self {
        block: 0,
        light: [0; 4],
        ao: [0; 4],
    };
}

pub fn mesh_section(
    cx: i32,
    cy: i32,
    cz: i32,
    center: &[i32],
    center_light: &[u8],
    neighbors: &[Option<&[i32]>; 6],
    neighbor_lights: &[Option<&[u8]>; 6],
) -> Option<SectionMesh> {
    debug_assert_eq!(center.len(), VOLUME);
    let padded = assemble_padded(center, neighbors);
    let padded_light = assemble_padded_light(center_light, neighbor_lights);
    let padded_solid = solid_map(&padded);
    let origin = [cx * DIM as i32, cy * DIM as i32, cz * DIM as i32];

    let mut vertices = Vec::new();
    let mut indices = Vec::new();
    let mut faces = 0u64;
    let mut quads = 0u64;

    for axis in 0..3usize {
        // u x v is the positive normal of `axis`.
        let (u_axis, v_axis) = match axis {
            0 => (1, 2), // y x z = +x
            1 => (2, 0), // z x x = +y
            _ => (0, 1), // x x y = +z
        };

        for sign in [1i32, -1] {
            for slice in 0..DIM {
                let mut mask = [FaceCell::EMPTY; DIM * DIM];

                for v in 0..DIM {
                    for u in 0..DIM {
                        let mut coord = [0i32; 3];
                        coord[axis] = slice as i32;
                        coord[u_axis] = u as i32;
                        coord[v_axis] = v as i32;

                        let mut neighbor = coord;
                        neighbor[axis] += sign;

                        let block = get_padded(&padded, coord[0], coord[1], coord[2]);
                        let adjacent = get_padded(&padded, neighbor[0], neighbor[1], neighbor[2]);

                        if block != 0
                            && !crate::material::is_cross(block)
                            && (adjacent == 0 || crate::material::is_cross(adjacent))
                        {
                            let (light, ao) = corner_light(
                                &padded_light,
                                &padded_solid,
                                coord,
                                axis,
                                sign,
                                u_axis,
                                v_axis,
                            );
                            mask[v * DIM + u] = FaceCell { block, light, ao };
                        }
                    }
                }

                greedy(
                    &mut mask,
                    &mut vertices,
                    &mut indices,
                    origin,
                    axis,
                    sign,
                    slice,
                    u_axis,
                    v_axis,
                    &mut faces,
                    &mut quads,
                );
            }
        }
    }

    // Plants are two crossed quads instead of a cube. Emit both sides so they
    // stay visible from every direction under back-face culling.
    for y in 0..DIM {
        for z in 0..DIM {
            for x in 0..DIM {
                let block = center[block_index(x, y, z)];
                if !crate::material::is_cross(block) {
                    continue;
                }
                let light = get_light(&padded_light, x as i32, y as i32, z as i32);
                emit_cross(
                    &mut vertices,
                    &mut indices,
                    origin,
                    [x as i32, y as i32, z as i32],
                    block,
                    light,
                );
                faces += 4;
                quads += 4;
            }
        }
    }

    if quads == 0 {
        return None;
    }

    Some(SectionMesh {
        vertices,
        indices,
        faces,
        quads,
    })
}

#[allow(clippy::too_many_arguments)]
fn greedy(
    mask: &mut [FaceCell; DIM * DIM],
    vertices: &mut Vec<Vertex>,
    indices: &mut Vec<u32>,
    origin: [i32; 3],
    axis: usize,
    sign: i32,
    slice: usize,
    u_axis: usize,
    v_axis: usize,
    faces: &mut u64,
    quads: &mut u64,
) {
    for v in 0..DIM {
        for u in 0..DIM {
            let cell = mask[v * DIM + u];
            if cell.block == 0 {
                continue;
            }

            let mut width = 1;
            while u + width < DIM && mask[v * DIM + u + width] == cell {
                width += 1;
            }

            let mut height = 1;
            'grow: while v + height < DIM {
                for k in 0..width {
                    if mask[(v + height) * DIM + u + k] != cell {
                        break 'grow;
                    }
                }
                height += 1;
            }

            for vv in 0..height {
                for uu in 0..width {
                    mask[(v + vv) * DIM + u + uu] = FaceCell::EMPTY;
                }
            }

            *faces += (width * height) as u64;
            *quads += 1;
            emit_quad(
                vertices, indices, origin, axis, sign, slice, u, v, width, height, cell, u_axis,
                v_axis,
            );
        }
    }
}

fn get_padded(padded: &[i32], x: i32, y: i32, z: i32) -> i32 {
    padded[pad_index(x, y, z)]
}

fn get_light(padded: &[u8], x: i32, y: i32, z: i32) -> u8 {
    padded[pad_index(x, y, z)]
}

fn is_solid(padded: &[u8], x: i32, y: i32, z: i32) -> bool {
    padded[pad_index(x, y, z)] != 0
}

/// Center section plus a one-cell border copied from the six neighbours.
/// Missing neighbours stay air: border faces render until the neighbour arrives.
fn assemble_padded(center: &[i32], neighbors: &[Option<&[i32]>; 6]) -> Vec<i32> {
    let mut padded = vec![0i32; PAD_VOLUME];

    for y in 0..DIM {
        for z in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, y as i32, z as i32)] = center[block_index(x, y, z)];
            }
        }
    }

    // Order: -x, +x, -y, +y, -z, +z.
    if let Some(n) = neighbors[0] {
        for y in 0..DIM {
            for z in 0..DIM {
                padded[pad_index(-1, y as i32, z as i32)] = n[block_index(DIM - 1, y, z)];
            }
        }
    }
    if let Some(n) = neighbors[1] {
        for y in 0..DIM {
            for z in 0..DIM {
                padded[pad_index(DIM as i32, y as i32, z as i32)] = n[block_index(0, y, z)];
            }
        }
    }
    if let Some(n) = neighbors[2] {
        for z in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, -1, z as i32)] = n[block_index(x, DIM - 1, z)];
            }
        }
    }
    if let Some(n) = neighbors[3] {
        for z in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, DIM as i32, z as i32)] = n[block_index(x, 0, z)];
            }
        }
    }
    if let Some(n) = neighbors[4] {
        for y in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, y as i32, -1)] = n[block_index(x, y, DIM - 1)];
            }
        }
    }
    if let Some(n) = neighbors[5] {
        for y in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, y as i32, DIM as i32)] = n[block_index(x, y, 0)];
            }
        }
    }

    padded
}

fn assemble_padded_light(center: &[u8], neighbors: &[Option<&[u8]>; 6]) -> Vec<u8> {
    // Missing light data defaults to full skylight, so a section arriving before
    // its light snapshot is visible instead of pitch black.
    let mut padded = vec![0xF0u8; PAD_VOLUME];
    for y in 0..DIM {
        for z in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, y as i32, z as i32)] = center[block_index(x, y, z)];
            }
        }
    }
    if let Some(n) = neighbors[0] {
        for y in 0..DIM {
            for z in 0..DIM {
                padded[pad_index(-1, y as i32, z as i32)] = n[block_index(DIM - 1, y, z)];
            }
        }
    }
    if let Some(n) = neighbors[1] {
        for y in 0..DIM {
            for z in 0..DIM {
                padded[pad_index(DIM as i32, y as i32, z as i32)] = n[block_index(0, y, z)];
            }
        }
    }
    if let Some(n) = neighbors[2] {
        for z in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, -1, z as i32)] = n[block_index(x, DIM - 1, z)];
            }
        }
    }
    if let Some(n) = neighbors[3] {
        for z in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, DIM as i32, z as i32)] = n[block_index(x, 0, z)];
            }
        }
    }
    if let Some(n) = neighbors[4] {
        for y in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, y as i32, -1)] = n[block_index(x, y, DIM - 1)];
            }
        }
    }
    if let Some(n) = neighbors[5] {
        for y in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, y as i32, DIM as i32)] = n[block_index(x, y, 0)];
            }
        }
    }
    padded
}

/// Which padded cells are full opaque cubes, for ambient occlusion. Leaves and
/// glass are deliberately not solid here, matching vanilla's AO rules.
fn solid_map(padded: &[i32]) -> Vec<u8> {
    padded
        .iter()
        .map(|&block| u8::from(block != 0 && crate::material::opacity(block) >= 15))
        .collect()
}

/// Horizon `ambient_occlusion`/`smooth_blend` reduced to full-cube faces.
///
/// Each corner samples the outward air cell, its two side neighbours and the
/// diagonal. The centre is weighted twice. The AO level is 3 when both sides
/// are open, 0 when both are blocked, and 1..=2 for one side.
#[allow(clippy::too_many_arguments)]
fn corner_light(
    light: &[u8],
    solid: &[u8],
    coord: [i32; 3],
    axis: usize,
    sign: i32,
    u_axis: usize,
    v_axis: usize,
) -> ([u8; 4], [u8; 4]) {
    let mut out = coord;
    out[axis] += sign;

    let mut packed = [0u8; 4];
    let mut ao = [0u8; 4];
    for (corner, slot) in packed.iter_mut().enumerate() {
        let (du, dv) = match corner {
            0 => (-1, -1),
            1 => (1, -1),
            2 => (1, 1),
            _ => (-1, 1),
        };
        let mut side_u = out;
        side_u[u_axis] += du;
        let mut side_v = out;
        side_v[v_axis] += dv;
        let mut diagonal = side_u;
        diagonal[v_axis] += dv;

        let mut sky = 0u32;
        let mut block = 0u32;
        let mut weight = 0u32;
        let samples = [(out, 2u32), (side_u, 1), (side_v, 1), (diagonal, 1)];
        for (cell, w) in samples {
            if is_solid(solid, cell[0], cell[1], cell[2]) {
                continue;
            }
            let value = get_light(light, cell[0], cell[1], cell[2]);
            sky += u32::from(value >> 4) * w;
            block += u32::from(value & 15) * w;
            weight += w;
        }
        if weight == 0 {
            let value = get_light(light, out[0], out[1], out[2]);
            sky = u32::from(value >> 4);
            block = u32::from(value & 15);
            weight = 1;
        }
        *slot = (((sky / weight) as u8) << 4) | (block / weight) as u8;

        let side1 = u8::from(is_solid(solid, side_u[0], side_u[1], side_u[2]));
        let side2 = u8::from(is_solid(solid, side_v[0], side_v[1], side_v[2]));
        let diag = u8::from(is_solid(solid, diagonal[0], diagonal[1], diagonal[2]));
        let level = if side1 == 1 && side2 == 1 {
            0
        } else {
            3u8.saturating_sub(side1 + side2 + diag)
        };
        ao[corner] = match level {
            3 => 255,
            2 => 204,
            1 => 153,
            _ => 102,
        };
    }
    (packed, ao)
}

#[allow(clippy::too_many_arguments)]
fn emit_quad(
    vertices: &mut Vec<Vertex>,
    indices: &mut Vec<u32>,
    origin: [i32; 3],
    axis: usize,
    sign: i32,
    slice: usize,
    u0: usize,
    v0: usize,
    width: usize,
    height: usize,
    cell: FaceCell,
    u_axis: usize,
    v_axis: usize,
) {
    let plane = slice as i32 + if sign > 0 { 1 } else { 0 };
    let u0 = u0 as i32;
    let v0 = v0 as i32;
    let u1 = u0 + width as i32;
    let v1 = v0 + height as i32;

    let mut corners = [[0i32; 3]; 4];
    let uv = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)];
    for (i, &(cu, cv)) in uv.iter().enumerate() {
        corners[i][axis] = plane;
        corners[i][u_axis] = cu;
        corners[i][v_axis] = cv;
    }

    let face = (axis as u8) * 2 + if sign > 0 { 1 } else { 0 };
    let layer = crate::material::layer(cell.block, face);
    let shade = crate::material::face_shade(face);

    let base = vertices.len() as u32;
    for (i, _) in corners.iter().enumerate() {
        // World-aligned UVs in block units. Sides are sampled top-down (the
        // tile's first row is the top of the block), so greedy quads keep
        // vanilla texel density and neighbouring sections line up.
        let (u, v) = match axis {
            0 => (corners[i][2], -corners[i][1]), // ±x: u along z, v down
            1 => (corners[i][0], corners[i][2]),  // ±y: u along x, v along z
            _ => (corners[i][0], -corners[i][1]), // ±z: u along x, v down
        };
        vertices.push(Vertex {
            position: [
                (origin[0] + corners[i][0]) as f32,
                (origin[1] + corners[i][1]) as f32,
                (origin[2] + corners[i][2]) as f32,
            ],
            uv: [u as f32, v as f32],
            layer,
            shade,
            light: f32::from(cell.light[i]),
            ao: u32::from(cell.ao[i]),
        });
    }

    // Winding: u x v = +axis, so reverse it for the negative direction.
    if sign > 0 {
        indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    } else {
        indices.extend_from_slice(&[base, base + 3, base + 2, base, base + 2, base + 1]);
    }
}

/// One crossed plant: two diagonal planes, each emitted front and back.
fn emit_cross(
    vertices: &mut Vec<Vertex>,
    indices: &mut Vec<u32>,
    origin: [i32; 3],
    local: [i32; 3],
    block: i32,
    light: u8,
) {
    let layer = crate::material::layer(block, 2);
    let shade = 217u32;
    let base_x = origin[0] + local[0];
    let base_y = origin[1] + local[1];
    let base_z = origin[2] + local[2];

    let height = if block == crate::material::B_SHORT_GRASS {
        0.8f32
    } else {
        1.0f32
    };
    let planes = [
        (
            [0.15f32, 0.15],
            [0.85f32, 0.85],
        ),
        (
            [0.85f32, 0.15],
            [0.15f32, 0.85],
        ),
    ];
    for (a, b) in planes {
        let corners = [
            [base_x as f32 + a[0], base_y as f32, base_z as f32 + a[1]],
            [base_x as f32 + b[0], base_y as f32, base_z as f32 + b[1]],
            [base_x as f32 + b[0], base_y as f32 + height, base_z as f32 + b[1]],
            [base_x as f32 + a[0], base_y as f32 + height, base_z as f32 + a[1]],
        ];
        let uvs = [(0.0f32, 1.0f32), (1.0, 1.0), (1.0, 0.0), (0.0, 0.0)];
        let base = vertices.len() as u32;
        for i in 0..4 {
            vertices.push(Vertex {
                position: corners[i],
                uv: [uvs[i].0, uvs[i].1],
                layer,
                shade,
                light: f32::from(light),
                ao: 255,
            });
        }
        indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
        indices.extend_from_slice(&[base, base + 3, base + 2, base, base + 2, base + 1]);
    }
}
