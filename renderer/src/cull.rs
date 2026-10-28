//! CPU frustum culling.
//!
//! Extracts the six clip planes from a column-major view-projection matrix
//! (Gribb–Hartmann) and tests section AABBs. Conservative by design: a section
//! is drawn if any part of its box intersects the frustum.

pub struct Frustum {
    planes: [[f32; 4]; 6],
}

impl Frustum {
    pub fn from_view_projection(m: &[f32; 16]) -> Self {
        // Rows of the column-major matrix.
        let row = |i: usize| [m[i], m[4 + i], m[8 + i], m[12 + i]];
        let r0 = row(0);
        let r1 = row(1);
        let r2 = row(2);
        let r3 = row(3);

        // WebGPU clip space: -w <= x,y <= w, 0 <= z <= w.
        let planes = [
            add(r3, r0),    // left
            sub(r3, r0),    // right
            add(r3, r1),    // bottom
            sub(r3, r1),    // top
            r2,             // near
            sub(r3, r2),    // far
        ];

        let mut out = [[0.0f32; 4]; 6];
        for (target, plane) in out.iter_mut().zip(planes) {
            *target = normalize(plane);
        }
        Self { planes: out }
    }

    pub fn intersects_aabb(&self, min: [f32; 3], max: [f32; 3]) -> bool {
        for plane in &self.planes {
            // Farthest corner along the plane normal.
            let x = if plane[0] >= 0.0 { max[0] } else { min[0] };
            let y = if plane[1] >= 0.0 { max[1] } else { min[1] };
            let z = if plane[2] >= 0.0 { max[2] } else { min[2] };
            if plane[0] * x + plane[1] * y + plane[2] * z + plane[3] < 0.0 {
                return false;
            }
        }
        true
    }
}

fn add(a: [f32; 4], b: [f32; 4]) -> [f32; 4] {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]]
}

fn sub(a: [f32; 4], b: [f32; 4]) -> [f32; 4] {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2], a[3] - b[3]]
}

fn normalize(plane: [f32; 4]) -> [f32; 4] {
    let length = (plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2]).sqrt();
    if length > f32::EPSILON {
        [
            plane[0] / length,
            plane[1] / length,
            plane[2] / length,
            plane[3] / length,
        ]
    } else {
        plane
    }
}
