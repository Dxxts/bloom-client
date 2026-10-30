//! View frustum from a column-major view-projection matrix (the JOML /
//! OpenGL convention Minecraft uses), with conservative AABB tests.

#[derive(Clone, Copy, Debug)]
pub struct Plane {
    pub normal: [f32; 3],
    pub d: f32,
}

impl Plane {
    fn normalized(a: f32, b: f32, c: f32, d: f32) -> Self {
        let len = (a * a + b * b + c * c).sqrt();
        if len > 0.0 {
            Plane { normal: [a / len, b / len, c / len], d: d / len }
        } else {
            Plane { normal: [0.0, 0.0, 0.0], d: 0.0 }
        }
    }

    /// Signed distance of the AABB corner furthest along the plane normal.
    #[inline]
    fn max_distance(&self, min: [f32; 3], max: [f32; 3]) -> f32 {
        let px = if self.normal[0] >= 0.0 { max[0] } else { min[0] };
        let py = if self.normal[1] >= 0.0 { max[1] } else { min[1] };
        let pz = if self.normal[2] >= 0.0 { max[2] } else { min[2] };
        self.normal[0] * px + self.normal[1] * py + self.normal[2] * pz + self.d
    }
}

#[derive(Clone, Debug)]
pub struct Frustum {
    pub planes: [Plane; 6],
}

impl Frustum {
    /// Gribb-Hartmann extraction. `m` is column-major: `m[col * 4 + row]`.
    pub fn from_matrix(m: &[f32; 16]) -> Self {
        let row = |r: usize| [m[r], m[4 + r], m[8 + r], m[12 + r]];
        let (r0, r1, r2, r3) = (row(0), row(1), row(2), row(3));
        let p = |s: f32, r: [f32; 4]| Plane::normalized(r3[0] + s * r[0], r3[1] + s * r[1], r3[2] + s * r[2], r3[3] + s * r[3]);
        Frustum { planes: [p(1.0, r0), p(-1.0, r0), p(1.0, r1), p(-1.0, r1), p(1.0, r2), p(-1.0, r2)] }
    }

    /// `false` only if the box is fully outside one plane (never culls a
    /// visible box; may keep some invisible ones near frustum corners).
    #[inline]
    pub fn intersects_aabb(&self, min: [f32; 3], max: [f32; 3]) -> bool {
        self.planes.iter().all(|p| p.max_distance(min, max) >= 0.0)
    }

    /// Tests 16³ sections given by their minimum corner relative to the
    /// camera. Writes one flag per section and returns how many are visible.
    pub fn cull_sections(&self, origins: &[[f32; 3]], out: &mut [bool], max_distance: f32) -> usize {
        let max_sq = max_distance * max_distance;
        let mut visible = 0;
        for (o, flag) in origins.iter().zip(out.iter_mut()) {
            let max = [o[0] + 16.0, o[1] + 16.0, o[2] + 16.0];
            // Distance from the camera (origin) to the nearest point of the box.
            let dx = 0f32.max(o[0]).max(-max[0]);
            let dy = 0f32.max(o[1]).max(-max[1]);
            let dz = 0f32.max(o[2]).max(-max[2]);
            let in_range = dx * dx + dy * dy + dz * dz <= max_sq;
            *flag = in_range && self.intersects_aabb(*o, max);
            visible += *flag as usize;
        }
        visible
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Column-major perspective (OpenGL depth -1..1) looking down -Z.
    fn perspective(fovy: f32, aspect: f32, near: f32, far: f32) -> [f32; 16] {
        let f = 1.0 / (fovy / 2.0).tan();
        let mut m = [0.0; 16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1.0;
        m[14] = 2.0 * far * near / (near - far);
        m
    }

    #[test]
    fn boxes_in_front_are_kept_and_behind_are_culled() {
        let fr = Frustum::from_matrix(&perspective(1.2, 16.0 / 9.0, 0.05, 512.0));
        assert!(fr.intersects_aabb([-1.0, -1.0, -20.0], [1.0, 1.0, -18.0]));
        assert!(!fr.intersects_aabb([-1.0, -1.0, 18.0], [1.0, 1.0, 20.0]));
        assert!(!fr.intersects_aabb([500.0, -1.0, -20.0], [502.0, 1.0, -18.0]));
        // Straddling the camera: must be kept.
        assert!(fr.intersects_aabb([-8.0, -8.0, -8.0], [8.0, 8.0, 8.0]));
    }

    #[test]
    fn distance_culling() {
        let fr = Frustum::from_matrix(&perspective(1.2, 1.0, 0.05, 4096.0));
        let origins = [[-8.0, -8.0, -40.0], [-8.0, -8.0, -400.0]];
        let mut out = [false; 2];
        assert_eq!(fr.cull_sections(&origins, &mut out, 100.0), 1);
        assert_eq!(out, [true, false]);
    }
}
