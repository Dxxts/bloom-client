//! Exact port of `net.minecraft.util.ARGB` (the parts used by block meshing).
//!
//! Colors are Java `int`s in ARGB order. All arithmetic mirrors the Java
//! source, including integer truncation and `float` rounding, so meshes match
//! vanilla byte for byte.

/// `-1`: the "no tint" sentinel used by `ARGB.multiply`.
pub const WHITE: i32 = -1;

#[inline]
pub const fn alpha(color: i32) -> i32 {
    ((color as u32) >> 24) as i32
}

#[inline]
pub const fn red(color: i32) -> i32 {
    (color >> 16) & 255
}

#[inline]
pub const fn green(color: i32) -> i32 {
    (color >> 8) & 255
}

#[inline]
pub const fn blue(color: i32) -> i32 {
    color & 255
}

#[inline]
pub const fn argb(a: i32, r: i32, g: i32, b: i32) -> i32 {
    ((a & 255) << 24) | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255)
}

/// `Mth.floor(float)`.
#[inline]
pub fn floor_f32(value: f32) -> i32 {
    let i = value as i32;
    if value < i as f32 {
        i - 1
    } else {
        i
    }
}

/// `ARGB.as8BitChannel`.
#[inline]
pub fn as_8bit_channel(value: f32) -> i32 {
    floor_f32(value * 255.0)
}

/// `ARGB.gray(float)`.
#[inline]
pub fn gray(brightness: f32) -> i32 {
    let c = as_8bit_channel(brightness);
    argb(255, c, c, c)
}

/// `ARGB.multiply(int, int)`.
#[inline]
pub fn multiply(lhs: i32, rhs: i32) -> i32 {
    if lhs == WHITE {
        rhs
    } else if rhs == WHITE {
        lhs
    } else {
        argb(
            alpha(lhs) * alpha(rhs) / 255,
            red(lhs) * red(rhs) / 255,
            green(lhs) * green(rhs) / 255,
            blue(lhs) * blue(rhs) / 255,
        )
    }
}

/// `ARGB.scaleRGB(int, float)`.
#[inline]
pub fn scale_rgb(color: i32, scale: f32) -> i32 {
    #[inline]
    fn channel(value: i32, scale: f32) -> i32 {
        // Math.clamp((long)(int)(value * scale), 0, 255)
        ((value as f32 * scale) as i32).clamp(0, 255)
    }
    argb(
        alpha(color),
        channel(red(color), scale),
        channel(green(color), scale),
        channel(blue(color), scale),
    )
}

/// `ARGB.toABGR`: the byte order `BufferBuilder` writes (R, G, B, A in memory
/// on little-endian machines).
#[inline]
pub const fn to_abgr(color: i32) -> i32 {
    let c = color as u32;
    ((c & 0xFF00_FF00) | ((c & 0x00FF_0000) >> 16) | ((c & 0x0000_00FF) << 16)) as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gray_matches_java() {
        // Values computed with the Java implementation.
        assert_eq!(gray(1.0), 0xFFFF_FFFFu32 as i32);
        assert_eq!(gray(0.5), 0xFF7F_7F7Fu32 as i32);
        assert_eq!(gray(0.2), 0xFF33_3333u32 as i32);
        assert_eq!(gray(0.0), 0xFF00_0000u32 as i32);
    }

    #[test]
    fn multiply_identity_and_channels() {
        let c = argb(255, 145, 189, 89);
        assert_eq!(multiply(WHITE, c), c);
        assert_eq!(multiply(c, WHITE), c);
        let g = gray(0.8);
        assert_eq!(multiply(g, c), argb(255, 204 * 145 / 255, 204 * 189 / 255, 204 * 89 / 255));
    }

    #[test]
    fn scale_truncates_like_java() {
        let c = argb(255, 255, 255, 255);
        assert_eq!(scale_rgb(c, 0.8), argb(255, 204, 204, 204));
        assert_eq!(scale_rgb(c, 0.6), argb(255, 153, 153, 153));
        assert_eq!(scale_rgb(argb(255, 127, 0, 1), 0.5), argb(255, 63, 0, 0));
    }

    #[test]
    fn abgr_swaps_red_and_blue() {
        assert_eq!(to_abgr(0x11223344), 0x11443322);
        assert_eq!(to_abgr(-1), -1);
    }
}
