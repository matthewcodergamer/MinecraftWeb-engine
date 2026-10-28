//! 16^3 greedy mesher with 18^3 padded neighbour assembly.
//!
//! Pure CPU meshing: no GPU device calls live here, so this can move into a
//! Worker later. Block IDs are opaque integers owned by the Java Brain; the M1
//! material rule is simply "0 = air, everything else = opaque".

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
    /// Vanilla directional face shading (top 1.0, bottom 0.5, N/S 0.8, E/W 0.6),
    /// baked per vertex so the fragment shader needs no normal.
    pub shade: u32,
}

impl Vertex {
    const ATTRS: [wgpu::VertexAttribute; 4] = [
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

pub fn mesh_section(
    cx: i32,
    cy: i32,
    cz: i32,
    center: &[i32],
    neighbors: &[Option<&[i32]>; 6],
) -> Option<SectionMesh> {
    debug_assert_eq!(center.len(), VOLUME);
    let padded = assemble_padded(center, neighbors);
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
                let mut mask = [0i32; DIM * DIM];

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

                        if block != 0 && adjacent == 0 {
                            mask[v * DIM + u] = block;
                        }
                    }
                }

                // Greedy rectangle growth over equal block IDs.
                for v in 0..DIM {
                    for u in 0..DIM {
                        let block = mask[v * DIM + u];
                        if block == 0 {
                            continue;
                        }

                        let mut width = 1;
                        while u + width < DIM && mask[v * DIM + u + width] == block {
                            width += 1;
                        }

                        let mut height = 1;
                        'grow: while v + height < DIM {
                            for k in 0..width {
                                if mask[(v + height) * DIM + u + k] != block {
                                    break 'grow;
                                }
                            }
                            height += 1;
                        }

                        for vv in 0..height {
                            for uu in 0..width {
                                mask[(v + vv) * DIM + u + uu] = 0;
                            }
                        }

                        faces += (width * height) as u64;
                        quads += 1;
                        emit_quad(
                            &mut vertices,
                            &mut indices,
                            origin,
                            axis,
                            sign,
                            slice,
                            u,
                            v,
                            width,
                            height,
                            block,
                            u_axis,
                            v_axis,
                        );
                    }
                }
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

fn get_padded(padded: &[i32], x: i32, y: i32, z: i32) -> i32 {
    padded[pad_index(x, y, z)]
}

/// Center section plus a one-cell border copied from the six neighbours.
/// Missing neighbours stay air: border faces render until the neighbour arrives.
fn assemble_padded(center: &[i32], neighbors: &[Option<&[i32]>; 6]) -> Vec<i32> {
    let mut padded = vec![0i32; PAD_VOLUME];

    for y in 0..DIM {
        for z in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, y as i32, z as i32)] =
                    center[block_index(x, y, z)];
            }
        }
    }

    // Order: -x, +x, -y, +y, -z, +z.
    if let Some(n) = neighbors[0] {
        for y in 0..DIM {
            for z in 0..DIM {
                padded[pad_index(-1, y as i32, z as i32)] =
                    n[block_index(DIM - 1, y, z)];
            }
        }
    }
    if let Some(n) = neighbors[1] {
        for y in 0..DIM {
            for z in 0..DIM {
                padded[pad_index(DIM as i32, y as i32, z as i32)] =
                    n[block_index(0, y, z)];
            }
        }
    }
    if let Some(n) = neighbors[2] {
        for z in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, -1, z as i32)] =
                    n[block_index(x, DIM - 1, z)];
            }
        }
    }
    if let Some(n) = neighbors[3] {
        for z in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, DIM as i32, z as i32)] =
                    n[block_index(x, 0, z)];
            }
        }
    }
    if let Some(n) = neighbors[4] {
        for y in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, y as i32, -1)] =
                    n[block_index(x, y, DIM - 1)];
            }
        }
    }
    if let Some(n) = neighbors[5] {
        for y in 0..DIM {
            for x in 0..DIM {
                padded[pad_index(x as i32, y as i32, DIM as i32)] =
                    n[block_index(x, y, 0)];
            }
        }
    }

    padded
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
    block: i32,
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
    let layer = crate::material::layer(block, face);
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
        });
    }

    // Winding: u x v = +axis, so reverse it for the negative direction.
    if sign > 0 {
        indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    } else {
        indices.extend_from_slice(&[base, base + 3, base + 2, base, base + 2, base + 1]);
    }
}
