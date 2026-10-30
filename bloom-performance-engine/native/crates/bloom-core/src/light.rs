//! Exact port of `net.minecraft.util.LightCoordsUtil`.
//!
//! Packed light coordinates hold block light in bits 4..8 and sky light in
//! bits 20..24 (`pack`). Smooth lighting uses the full byte of each half.

/// `LightCoordsUtil.FULL_BRIGHT`.
pub const FULL_BRIGHT: i32 = 15_728_880;

#[inline]
pub const fn pack(block: i32, sky: i32) -> i32 {
    (block << 4) | (sky << 20)
}

#[inline]
pub const fn block(packed: i32) -> i32 {
    (packed >> 4) & 15
}

#[inline]
pub const fn sky(packed: i32) -> i32 {
    (packed >> 20) & 15
}

#[inline]
pub const fn with_block(coords: i32, block: i32) -> i32 {
    (coords & 16_711_680) | (block << 4)
}

#[inline]
pub const fn smooth_pack(block: i32, sky: i32) -> i32 {
    (block & 255) | ((sky & 255) << 16)
}

#[inline]
pub const fn smooth_block(packed: i32) -> i32 {
    packed & 255
}

#[inline]
pub const fn smooth_sky(packed: i32) -> i32 {
    (packed >> 16) & 255
}

/// `LightCoordsUtil.lightCoordsWithEmission`.
#[inline]
pub fn with_emission(light: i32, emission: i32) -> i32 {
    if emission == 0 {
        light
    } else {
        let s = sky(light).max(emission);
        let b = block(light).max(emission);
        pack(b, s)
    }
}

/// `LightCoordsUtil.smoothBlend`.
#[inline]
pub fn smooth_blend(mut n1: i32, mut n2: i32, mut n3: i32, center: i32) -> i32 {
    if sky(center) > 2 || block(center) > 2 {
        if n1 == 0 {
            n1 = center;
        } else if sky(n1) == 0 {
            n1 |= center & 16_711_680;
        }
        if n2 == 0 {
            n2 = center;
        } else if sky(n2) == 0 {
            n2 |= center & 16_711_680;
        }
        if n3 == 0 {
            n3 = center;
        } else if sky(n3) == 0 {
            n3 |= center & 16_711_680;
        }
    }
    (n1.wrapping_add(n2).wrapping_add(n3).wrapping_add(center) >> 2) & 16_711_935
}

/// `LightCoordsUtil.smoothWeightedBlend`.
#[inline]
#[allow(clippy::too_many_arguments)]
pub fn smooth_weighted_blend(c1: i32, c2: i32, c3: i32, c4: i32, w1: f32, w2: f32, w3: f32, w4: f32) -> i32 {
    let s = (smooth_sky(c1) as f32 * w1 + smooth_sky(c2) as f32 * w2 + smooth_sky(c3) as f32 * w3 + smooth_sky(c4) as f32 * w4) as i32;
    let b = (smooth_block(c1) as f32 * w1
        + smooth_block(c2) as f32 * w2
        + smooth_block(c3) as f32 * w3
        + smooth_block(c4) as f32 * w4) as i32;
    smooth_pack(b, s)
}

/// `LightCoordsUtil.getLightCoords(getter, level, state, pos)` given the raw
/// brightness at `pos` (`pack(block, sky)`) and the properties of `state`.
#[inline]
pub fn light_coords(raw: i32, emissive_rendering: bool, light_emission: i32) -> i32 {
    if emissive_rendering {
        FULL_BRIGHT
    } else if block(raw) < light_emission {
        with_block(raw, light_emission)
    } else {
        raw
    }
}

/// Decodes the snapshot's light byte (`sky << 4 | block`) into `pack(block, sky)`.
#[inline]
pub const fn unpack_snapshot_byte(byte: u8) -> i32 {
    pack((byte & 15) as i32, (byte >> 4) as i32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pack_roundtrip() {
        for b in 0..16 {
            for s in 0..16 {
                let p = pack(b, s);
                assert_eq!(block(p), b);
                assert_eq!(sky(p), s);
                assert_eq!(unpack_snapshot_byte(((s << 4) | b) as u8), p);
            }
        }
        assert_eq!(pack(15, 15), FULL_BRIGHT);
    }

    #[test]
    fn smooth_blend_matches_java() {
        // Reference values from the Java implementation.
        let full = pack(15, 15);
        assert_eq!(smooth_blend(full, full, full, full), full);
        assert_eq!(smooth_blend(0, 0, 0, full), full);
        let dark = pack(0, 0);
        assert_eq!(smooth_blend(dark, dark, dark, dark), 0);
        // Sky 0 neighbours inherit the centre's sky.
        let center = pack(4, 12);
        let n = pack(8, 0);
        let expected = ((n | (center & 16_711_680)) * 3 + center) >> 2 & 16_711_935;
        assert_eq!(smooth_blend(n, n, n, center), expected);
    }

    #[test]
    fn light_coords_respects_emission() {
        let raw = pack(3, 10);
        assert_eq!(light_coords(raw, false, 0), raw);
        assert_eq!(light_coords(raw, false, 12), pack(12, 10));
        assert_eq!(light_coords(raw, true, 0), FULL_BRIGHT);
        assert_eq!(with_emission(raw, 0), raw);
        assert_eq!(with_emission(raw, 14), pack(14, 14));
    }
}
