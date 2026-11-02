package com.bloomclient.client.ui;

/**
 * Bloom's visual identity: charcoal surfaces, mint accent. ARGB colors.
 *
 * <p>Two looks share the same names: <b>glass</b> (default), where panels are
 * translucent over the blurred world and surfaces are light veils, and
 * <b>solid</b>, the opaque look. {@link #apply} switches them; screens call it
 * every frame so the setting takes effect immediately. Popups, tooltips and
 * dialogs always use the opaque {@link #POPUP} so they stay readable over
 * anything.
 */
public final class BloomTheme {
	public static final int BACKDROP_SOLID = 0xB0050706, BACKDROP_GLASS = 0x70050706;
	public static int BACKDROP = BACKDROP_GLASS;
	public static int BG = 0xF00B0D0C;
	/** Main menu panel. */
	public static int PANEL;
	/** Sidebar, drawn over the panel. */
	public static int SIDEBAR;
	/** HUD Studio toolbar, drawers and inspector (over the live, unblurred game). */
	public static int STUDIO;
	/** Popups, dropdowns, tooltips, dialogs, toasts: always opaque. */
	public static final int POPUP = 0xFF171B1A;
	public static int SURFACE;
	public static int SURFACE_2;
	public static int SURFACE_HOVER;
	public static int SURFACE_ACTIVE;
	public static int BORDER;
	public static int BORDER_STRONG;

	public static final int TEXT = 0xFFE9EEEC;
	public static final int TEXT_DIM = 0xFFA3ADA9;
	public static final int TEXT_MUTED = 0xFF7A8480;

	public static final int ACCENT = 0xFF5CE1A8;
	public static final int ACCENT_HOVER = 0xFF7DEBBC;
	public static final int ACCENT_DIM = 0xFF2F8A64;
	public static final int ACCENT_SOFT = 0x2E5CE1A8;
	public static final int ACCENT_TEXT = 0xFF06140E;

	public static final int DANGER = 0xFFE5636B;
	public static final int DANGER_SOFT = 0x30E5636B;
	public static final int WARNING = 0xFFE8B54D;
	public static final int WARNING_SOFT = 0x30E8B54D;
	public static final int INFO = 0xFF6CB4EE;

	public static final int RADIUS = 4;
	public static final int RADIUS_SMALL = 3;

	/** Swatches offered by the color picker. */
	public static final int[] PALETTE = {
			0xFFFFFFFF, 0xFFE9EEEC, 0xFFA3ADA9, 0xFF6E7874, 0xFF262C2A, 0xFF000000,
			0xFF5CE1A8, 0xFF3DD68C, 0xFF2F8A64, 0xFF6CB4EE, 0xFF8B8CF6, 0xFFC58AF0,
			0xFFF07AB0, 0xFFE5636B, 0xFFF29A5C, 0xFFE8B54D, 0xFFF1E05A, 0xFFA8E05F
	};

	private static boolean glass = true;

	static {
		set(true);
	}

	private BloomTheme() {
	}

	/** Switches between the glass and the solid look. */
	public static void apply(boolean useGlass) {
		if (useGlass == glass) return;
		glass = useGlass;
		set(useGlass);
	}

	private static void set(boolean useGlass) {
		if (useGlass) {
			BACKDROP = BACKDROP_GLASS;
			PANEL = 0xC40D100F;
			SIDEBAR = 0x40000000;
			STUDIO = 0xE60E1110;
			SURFACE = 0x0FFFFFFF;
			SURFACE_2 = 0x17FFFFFF;
			SURFACE_HOVER = 0x21FFFFFF;
			SURFACE_ACTIVE = 0x2CFFFFFF;
			BORDER = 0x1CFFFFFF;
			BORDER_STRONG = 0x30FFFFFF;
		} else {
			BACKDROP = BACKDROP_SOLID;
			PANEL = 0xFF101312;
			SIDEBAR = 0xFF0D0F0E;
			STUDIO = 0xFF101312;
			SURFACE = 0xFF161A19;
			SURFACE_2 = 0xFF1C2120;
			SURFACE_HOVER = 0xFF232927;
			SURFACE_ACTIVE = 0xFF2A312F;
			BORDER = 0xFF262C2A;
			BORDER_STRONG = 0xFF343C39;
		}
	}

	public static boolean glass() {
		return glass;
	}

	public static int withAlpha(int color, float alpha) {
		int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
		return (a << 24) | (color & 0xFFFFFF);
	}

	public static int lerp(int a, int b, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8)
				| Math.round(ab + (bb - ab) * t);
	}
}
