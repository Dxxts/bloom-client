package com.bloomclient.engine.bridge;

/** Mirror of {@code bloom_core::protocol} (version 1). Keep both in sync. */
public final class Protocol {
	private Protocol() {
	}

	public static final int VERSION = 1;

	public static final int SNAPSHOT_MAGIC = magic("BLMS");
	public static final int STATE_TABLE_MAGIC = magic("BLST");
	public static final int SHAPE_PAIRS_MAGIC = magic("BLSP");
	public static final int MODEL_MAGIC = magic("BLMD");

	public static final int STATE_DIM = 20;
	public static final int STATE_MIN = -2;
	public static final int LIGHT_DIM = 18;
	public static final int LIGHT_MIN = -1;

	public static final int SNAPSHOT_HEADER_SIZE = 64;
	public static final int SNAPSHOT_STATES_OFFSET = SNAPSHOT_HEADER_SIZE;
	public static final int SNAPSHOT_LIGHT_OFFSET = SNAPSHOT_STATES_OFFSET + STATE_DIM * STATE_DIM * STATE_DIM * 4;
	public static final int SNAPSHOT_MODELS_OFFSET = SNAPSHOT_LIGHT_OFFSET + LIGHT_DIM * LIGHT_DIM * LIGHT_DIM;
	public static final int SNAPSHOT_TABLES_OFFSET = SNAPSHOT_MODELS_OFFSET + 4096 * 4;
	public static final int TINT_ENTRY_SIZE = 16;
	public static final int MAX_TINTS = 3;
	public static final int OFFSET_ENTRY_SIZE = 16;
	/** Largest possible snapshot: every block has a tint and an offset entry. */
	public static final int SNAPSHOT_MAX_SIZE = SNAPSHOT_TABLES_OFFSET + 4096 * (TINT_ENTRY_SIZE + OFFSET_ENTRY_SIZE);

	public static final int FLAG_AMBIENT_OCCLUSION = 1;
	public static final int FLAG_CUTOUT_LEAVES = 1 << 1;

	public static final int MODEL_NONE = 0;
	public static final int MODEL_JAVA = -1;

	public static final int LIGHT_MASK_BYTES = (LIGHT_DIM * LIGHT_DIM * LIGHT_DIM + 7) / 8;

	public static final int RESULT_SIZE = 128;
	public static final int LAYER_COUNT = 3;
	public static final int VERTEX_SIZE = 28;

	public static final int STATUS_OK = 0;
	public static final int STATUS_STALE_TABLES = 1;
	public static final int STATUS_INVALID_INPUT = 2;
	public static final int STATUS_PANIC = 3;
	public static final int STATUS_INTERNAL = 4;

	public static final int TABLE_HEADER_SIZE = 16;
	public static final int STATE_RECORD_SIZE = 32;

	public static final int STATE_AIR = 1;
	public static final int STATE_SOLID_RENDER = 1 << 1;
	public static final int STATE_LIGHT_PERMEABLE = 1 << 2;
	public static final int STATE_EMISSIVE_RENDERING = 1 << 3;
	public static final int STATE_COLLISION_FULL_BLOCK = 1 << 4;
	public static final int STATE_DYNAMIC = 1 << 5;
	public static final int STATE_LEAVES = 1 << 6;
	public static final int STATE_TINTED = 1 << 7;

	public static final int SKIP_NONE = 0;
	public static final int SKIP_SAME_BLOCK = 1;
	public static final int SKIP_LEAVES = 2;

	public static final int FACE_EMPTY = 0;
	public static final int FACE_BLOCK = 1;
	public static final int FACE_PARTIAL = 2;
	public static final int FACE_PARTIAL_COVERING = 3;
	public static final int NO_SHAPE = 0xFFFF;

	public static final int MODEL_HEADER_SIZE = 32;
	public static final int QUAD_RECORD_SIZE = 96;
	public static final int MODEL_FLAG_USE_AO = 1;
	public static final int CULL_NONE = 6;
	public static final int NO_SHADE_OVERRIDE = 0xFF;

	public static final int STATS_SLOTS = 16;

	private static int magic(String s) {
		return (s.charAt(0) & 0xFF) | (s.charAt(1) & 0xFF) << 8 | (s.charAt(2) & 0xFF) << 16 | (s.charAt(3) & 0xFF) << 24;
	}
}
