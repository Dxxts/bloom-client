package com.bloomclient.engine.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;

/** Loads {@code bloom_native} and checks the protocol version. */
public final class NativeLibrary {
	private static final String RESOURCE_DIR = "/natives/";

	private NativeLibrary() {
	}

	/**
	 * Loads the library. Order: {@code -Dbloom.native.path} (development),
	 * then the copy bundled in the mod jar, extracted to {@code cacheDir}
	 * under a content-hashed name so concurrent game instances never
	 * overwrite a library another process has loaded.
	 */
	public static void load(Path cacheDir) throws IOException {
		String override = System.getProperty("bloom.native.path");
		if (override != null && !override.isBlank()) {
			System.load(Path.of(override).toAbsolutePath().toString());
		} else {
			String platform = platform();
			String resource = RESOURCE_DIR + platform + "/" + System.mapLibraryName("bloom_native");
			byte[] bytes;
			try (InputStream in = NativeLibrary.class.getResourceAsStream(resource)) {
				if (in == null) {
					throw new IOException("bloom_native is not bundled for " + platform);
				}
				bytes = in.readAllBytes();
			}
			String hash;
			try {
				hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
			} catch (Exception e) {
				throw new IOException(e);
			}
			Files.createDirectories(cacheDir);
			Path target = cacheDir.resolve(hash + "-" + System.mapLibraryName("bloom_native"));
			if (!Files.exists(target)) {
				Path tmp = Files.createTempFile(cacheDir, "bloom_native", ".tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
			}
			System.load(target.toAbsolutePath().toString());
		}
		int version = NativeBridge.protocolVersion();
		if (version != Protocol.VERSION) {
			throw new IOException("bloom_native speaks protocol " + version + ", expected " + Protocol.VERSION);
		}
	}

	static String platform() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
		String osName = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
		String archName = arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : arch;
		return osName + "-" + archName;
	}

	/** The calling thread's last native error message. */
	public static String lastError() {
		long buf = MemoryUtil.nmemAlloc(1024);
		try {
			int len = NativeBridge.lastError(buf, 1024);
			return len <= 0 ? "" : MemoryUtil.memUTF8(buf, Math.min(len, 1024));
		} finally {
			MemoryUtil.nmemFree(buf);
		}
	}
}
