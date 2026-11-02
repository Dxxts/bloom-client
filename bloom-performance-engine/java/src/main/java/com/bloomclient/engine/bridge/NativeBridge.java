package com.bloomclient.engine.bridge;

/**
 * JNI surface of {@code bloom_native} (crate {@code bloom-ffi}).
 *
 * <p>Only addresses of off-heap buffers, lengths and status codes cross the
 * boundary; no Java object is passed to native code. Buffers must stay alive
 * for the duration of each call. See {@code docs/PROTOCOLLO.md}.
 */
public final class NativeBridge {
	private NativeBridge() {
	}

	public static native int protocolVersion();

	/** Replaces the state table and drops every registered model. */
	public static native int installTables(long statesAddr, int statesLen, long pairsAddr, int pairsLen);

	/** Returns the model id ({@code >= 1}) or a negated status code. */
	public static native int registerModel(long addr, int len);

	/** Returns a context handle, or 0 on failure. One context per thread. */
	public static native long createContext();

	public static native void destroyContext(long ctx);

	/**
	 * Writes the {@link Protocol#PLAN_SIZE}-byte plan: the light positions the
	 * next {@link #meshSection} may read, then the fluid blocks Java must describe.
	 */
	public static native int planLight(long ctx, long snapshotAddr, int snapshotLen, long maskAddr);

	/** Meshes one snapshot and writes {@link Protocol#RESULT_SIZE} bytes at {@code resultAddr}. */
	public static native int meshSection(long ctx, long snapshotAddr, int snapshotLen, long resultAddr);

	public static native int readStats(long addr, int slots);

	public static native void resetStats();

	/** Copies the calling thread's last error (UTF-8) and returns its full length. */
	public static native int lastError(long addr, int capacity);

	public static native void shutdown();
}
