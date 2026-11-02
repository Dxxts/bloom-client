package com.bloomclient.client.ui;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Shape primitives built from {@code fill}: rounded rectangles, rings,
 * circles and soft shadows. Rows never overlap, so translucent colors blend
 * correctly.
 *
 * <p>While {@link #fine} is above 1 (Bloom screens set it to the number of
 * physical pixels per canvas unit), rounded corners are drawn at physical
 * resolution from anti-aliased mask textures. With {@code fine == 1} (HUD,
 * GUI coordinates) shapes use the classic per-unit staircase.
 */
public final class Draw {
	public static final int MAX_RADIUS = 12;
	private static final int[][] INSETS = new int[MAX_RADIUS + 1][];
	/** Physical pixels per drawing unit for anti-aliased shapes; 1 = off. */
	private static int fine = 1;

	static {
		for (int r = 0; r <= MAX_RADIUS; r++) {
			INSETS[r] = new int[r];
			for (int i = 0; i < r; i++) {
				double dy = r - i - 0.5;
				INSETS[r][i] = r - (int) Math.floor(Math.sqrt(r * r - dy * dy) + 0.5);
			}
		}
	}

	private Draw() {
	}

	/** Enables anti-aliased shapes at {@code physicalPerUnit} pixels per unit (1 disables). */
	public static void setFine(int physicalPerUnit) {
		fine = Math.max(1, physicalPerUnit);
	}

	public static int fine() {
		return fine;
	}

	private static int clampRadius(int r, int w, int h) {
		return Math.max(0, Math.min(Math.min(r, MAX_RADIUS), Math.min(w, h) / 2));
	}

	public static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
		if (w > 0 && h > 0 && (color >>> 24) != 0) {
			g.fill(x, y, x + w, y + h, color);
		}
	}

	// ---------------------------------------------------------------- anti-aliased primitives (physical pixels)
	//
	// Corners come from small white masks (a full circle or ring of radius R,
	// thickness T, coverage computed with 4x4 supersampling) generated once per
	// (R, T) and uploaded as textures. A rounded rectangle is then 4 tinted
	// corner blits plus 3 fills, fewer draw calls than the unit staircase.

	private static final int MAX_MASK_RADIUS = 96;
	private static final Map<Long, Identifier> MASKS = new HashMap<>();

	/** Mask texture for a circle (T = 0) or ring (T > 0) of radius R, 2R x 2R pixels. */
	private static Identifier mask(int R, int T) {
		long key = ((long) R << 16) | T;
		Identifier id = MASKS.get(key);
		if (id != null) return id;
		int d = 2 * R;
		NativeImage img = new NativeImage(d, d, false);
		double inner = T > 0 ? Math.max(0, R - T) : -1;
		for (int y = 0; y < d; y++) {
			for (int x = 0; x < d; x++) {
				int hits = 0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						double dx = x + (sx + 0.5) / 4 - R, dy = y + (sy + 0.5) / 4 - R;
						double dist = Math.sqrt(dx * dx + dy * dy);
						if (dist <= R && dist >= inner) hits++;
					}
				}
				int a = Math.round(hits * 255f / 16f);
				img.setPixel(x, y, (a << 24) | 0xFFFFFF);
			}
		}
		id = Identifier.fromNamespaceAndPath("bloom", "ui/mask_" + R + "_" + T);
		Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Bloom UI mask " + R + "/" + T, img));
		MASKS.put(key, id);
		return id;
	}

	/** Blits one quadrant (qx, qy in {0, 1}) of a mask at (X, Y), R x R pixels, tinted. */
	private static void corner(GuiGraphicsExtractor g, Identifier mask, int X, int Y, int R, int qx, int qy, int color) {
		g.blit(RenderPipelines.GUI_TEXTURED, mask, X, Y, qx * R, qy * R, R, R, R, R, 2 * R, 2 * R, color);
	}

	private static void fineRoundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color, boolean rightRound) {
		int s = fine;
		int R = Math.min(r * s, MAX_MASK_RADIUS);
		g.pose().pushMatrix();
		g.pose().scale(1f / s, 1f / s);
		int X = x * s, Y = y * s, W = w * s, H = h * s;
		Identifier m = mask(R, 0);
		corner(g, m, X, Y, R, 0, 0, color);
		corner(g, m, X, Y + H - R, R, 0, 1, color);
		if (rightRound) {
			corner(g, m, X + W - R, Y, R, 1, 0, color);
			corner(g, m, X + W - R, Y + H - R, R, 1, 1, color);
			g.fill(X + R, Y, X + W - R, Y + R, color);
			g.fill(X + R, Y + H - R, X + W - R, Y + H, color);
		} else {
			g.fill(X + R, Y, X + W, Y + R, color);
			g.fill(X + R, Y + H - R, X + W, Y + H, color);
		}
		if (H - 2 * R > 0) g.fill(X, Y + R, X + W, Y + H - R, color);
		g.pose().popMatrix();
	}

	private static void fineRing(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int t, int color) {
		int s = fine;
		g.pose().pushMatrix();
		g.pose().scale(1f / s, 1f / s);
		ringPx(g, x * s, y * s, w * s, h * s, Math.min(r * s, MAX_MASK_RADIUS), t * s, color);
		g.pose().popMatrix();
	}

	/** Anti-aliased ring in physical pixels: outer radius R (at least T), thickness T. */
	private static void ringPx(GuiGraphicsExtractor g, int X, int Y, int W, int H, int R, int T, int color) {
		R = Math.max(R, T);
		Identifier m = mask(R, T);
		corner(g, m, X, Y, R, 0, 0, color);
		corner(g, m, X + W - R, Y, R, 1, 0, color);
		corner(g, m, X, Y + H - R, R, 0, 1, color);
		corner(g, m, X + W - R, Y + H - R, R, 1, 1, color);
		g.fill(X + R, Y, X + W - R, Y + T, color);
		g.fill(X + R, Y + H - T, X + W - R, Y + H, color);
		g.fill(X, Y + R, X + T, Y + H - R, color);
		g.fill(X + W - T, Y + R, X + W, Y + H - R, color);
	}

	// ---------------------------------------------------------------- shapes

	public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
			return;
		}
		r = clampRadius(r, w, h);
		if (r == 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		if (fine > 1) {
			fineRoundRect(g, x, y, w, h, r, color, true);
			return;
		}
		int[] inset = INSETS[r];
		if (h - 2 * r > 0) {
			g.fill(x, y + r, x + w, y + h - r, color);
		}
		for (int i = 0; i < r; i++) {
			int in = inset[i];
			g.fill(x + in, y + i, x + w - in, y + i + 1, color);
			g.fill(x + in, y + h - 1 - i, x + w - in, y + h - i, color);
		}
	}

	/** Rounded rectangle with only the left corners rounded (sidebar items). */
	public static void roundRectLeft(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = clampRadius(r, w, h);
		if (fine > 1 && r > 0) {
			fineRoundRect(g, x, y, w, h, r, color, false);
			return;
		}
		int[] inset = INSETS[r];
		g.fill(x, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			g.fill(x + inset[i], y + i, x + w, y + i + 1, color);
			g.fill(x + inset[i], y + h - 1 - i, x + w, y + h - i, color);
		}
	}

	/** Ring of thickness {@code t} following a rounded rectangle. */
	public static void roundRectOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int t, int color) {
		if (w <= 0 || h <= 0 || t <= 0 || (color >>> 24) == 0) {
			return;
		}
		r = clampRadius(r, w, h);
		if (2 * t >= w || 2 * t >= h) {
			roundRect(g, x, y, w, h, r, color);
			return;
		}
		if (fine > 1) {
			fineRing(g, x, y, w, h, r, t, color);
			return;
		}
		int ri = Math.max(0, r - t);
		int band = Math.max(r, t + ri);
		int iw = w - 2 * t;
		for (int i = 0; i < band && i < h / 2; i++) {
			row(g, x, y + i, w, iw, t, r, ri, i, color);
			row(g, x, y + h - 1 - i, w, iw, t, r, ri, i, color);
		}
		int top = y + band, bottom = y + h - band;
		if (bottom > top) {
			g.fill(x, top, x + t, bottom, color);
			g.fill(x + w - t, top, x + w, bottom, color);
		}
	}

	/**
	 * Hairline border (one physical pixel, or one unit without anti-aliasing):
	 * sleeker than {@link #roundRectOutline} for panel and card edges.
	 */
	public static void hairline(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (fine <= 1) {
			roundRectOutline(g, x, y, w, h, r, 1, color);
			return;
		}
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
		r = clampRadius(r, w, h);
		int s = fine;
		g.pose().pushMatrix();
		g.pose().scale(1f / s, 1f / s);
		ringPx(g, x * s, y * s, w * s, h * s, r * s, 1, color);
		g.pose().popMatrix();
	}

	private static void row(GuiGraphicsExtractor g, int x, int y, int w, int iw, int t, int r, int ri, int i, int color) {
		int outer = i < r ? INSETS[r][i] : 0;
		if (i < t) {
			g.fill(x + outer, y, x + w - outer, y + 1, color);
			return;
		}
		int j = i - t;
		int inner = j < ri ? INSETS[ri][j] : 0;
		int innerStart = x + t + inner;
		int innerEnd = x + t + iw - inner;
		if (innerStart > x + outer) g.fill(x + outer, y, innerStart, y + 1, color);
		if (x + w - outer > innerEnd) g.fill(innerEnd, y, x + w - outer, y + 1, color);
	}

	public static void circle(GuiGraphicsExtractor g, int cx, int cy, int radius, int color) {
		roundRect(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
	}

	/** Soft drop shadow under a rounded panel. */
	public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int strength) {
		// Already soft: drawn at unit resolution, which costs a fraction of the anti-aliased path.
		int saved = fine;
		fine = 1;
		try {
			for (int i = 1; i <= strength; i++) {
				int alpha = Math.max(4, 40 / i);
				roundRectOutline(g, x - i, y - i + 1, w + 2 * i, h + 2 * i, r + i, 1, alpha << 24);
			}
		} finally {
			fine = saved;
		}
	}

	/** Horizontal gradient from {@code left} to {@code right}, drawn in vertical strips. */
	public static void hGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int left, int right, int strips) {
		if (w <= 0 || h <= 0) return;
		strips = Math.max(1, Math.min(strips, w));
		for (int s = 0; s < strips; s++) {
			int x0 = x + w * s / strips, x1 = x + w * (s + 1) / strips;
			g.fill(x0, y, x1, y + h, BloomTheme.lerp(left, right, strips == 1 ? 0 : (float) s / (strips - 1)));
		}
	}

	/** Checkerboard shown behind translucent colors. */
	public static void checker(GuiGraphicsExtractor g, int x, int y, int w, int h, int cell) {
		g.fill(x, y, x + w, y + h, 0xFF9A9A9A);
		for (int cy = 0; cy < h; cy += cell) {
			for (int cx = ((cy / cell) & 1) * cell; cx < w; cx += cell * 2) {
				g.fill(x + cx, y + cy, x + Math.min(w, cx + cell), y + Math.min(h, cy + cell), 0xFF5E5E5E);
			}
		}
	}
}
