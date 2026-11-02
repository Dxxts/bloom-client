package com.bloomclient.engine.compatibility;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.jspecify.annotations.Nullable;

/**
 * Local, updatable compatibility database ({@code bloom-compat-db.json}).
 *
 * <p>Two copies can exist: the one bundled in the mod jar and the one the
 * launcher downloads ({@code -Dbloom.compatDb=<path>}). Each must carry a
 * valid Ed25519 signature ({@link DatabaseSignature}); the valid copy with the
 * highest {@code sequence} wins, so an old signed file cannot replace a newer
 * one. An invalid download is reported and ignored: the database never stops
 * the game and never disables a mod, it only chooses the engine mode.
 */
public final class CompatibilityDatabase {
	public static final int SCHEMA = 1;
	static final String BUNDLED = "/bloom/bloom-compat-db.json";

	/** What Bloom does with a mod; see {@code docs/COMPATIBILITA.md}. */
	public enum Status {
		/** The mod's model classes are registered as static: meshed natively. */
		NATIVE_ADAPTER,
		/** No adapter needed: vanilla model classes, verified hooks or none. */
		AUTOMATIC_ADAPTER,
		/** The mod's blocks are tesselated by the vanilla Java renderer. */
		JAVA_FALLBACK,
		/** The mod replaces or alters the world renderer: the engine turns off. */
		ENGINE_OFF,
		/** Known to the database, not tested yet: the analyzer decides. */
		UNTESTED
	}

	public enum VulkanSupport {
		SUPPORTED,
		UNSUPPORTED,
		UNTESTED
	}

	public static final class TestResult {
		public String date;
		public String bloomVersion;
		public String backend;
		/** PASS, FAIL or PARTIAL. */
		public String result;
		public String notes;
	}

	public static final class Entry {
		public String modId;
		/** Fabric version predicate of the mod version ("*" for any). */
		public String versions = "*";
		public String loader = "fabric";
		/** Minecraft version predicate. */
		public String minecraft = "*";
		public Status status = Status.UNTESTED;
		public int adapterVersion;
		public List<String> knownIssues = new ArrayList<>();
		public List<String> renderingFeatures = new ArrayList<>();
		public VulkanSupport vulkan = VulkanSupport.UNTESTED;
		/** The mod hooks terrain classes and the combination was validated. */
		public boolean terrainHooksVerified;
		/** Block namespaces the status applies to (default: the mod id). */
		public List<String> namespaces = new ArrayList<>();
		/** Model classes verified as static (used with {@link Status#NATIVE_ADAPTER}). */
		public List<String> staticModelClasses = new ArrayList<>();
		public List<TestResult> tests = new ArrayList<>();

		boolean matches(String id, String version, String modLoader, String minecraftVersion) {
			return modId.equals(id) && loader.equalsIgnoreCase(modLoader) && test(minecraft, minecraftVersion) && test(versions, version);
		}

		private static boolean test(String predicate, String version) {
			if (predicate == null || predicate.equals("*")) {
				return true;
			}
			try {
				return VersionPredicate.parse(predicate).test(Version.parse(version));
			} catch (VersionParsingException e) {
				return false;
			}
		}
	}

	public static final class Data {
		public int schema;
		public long sequence;
		public String generated;
		public List<Entry> entries = new ArrayList<>();
	}

	private static final Gson GSON = new Gson();

	private final @Nullable Data data;
	private final String source;
	private final List<String> problems;

	private CompatibilityDatabase(@Nullable Data data, String source, List<String> problems) {
		this.data = data;
		this.source = source;
		this.problems = problems;
	}

	public static CompatibilityDatabase empty(String why) {
		return new CompatibilityDatabase(null, "none", new ArrayList<>(List.of(why)));
	}

	/** Bundled copy plus the launcher's download, if any. */
	public static CompatibilityDatabase load(@Nullable Path downloaded) {
		List<String> problems = new ArrayList<>();
		Data bundled = null;
		try (InputStream in = CompatibilityDatabase.class.getResourceAsStream(BUNDLED);
				InputStream sig = CompatibilityDatabase.class.getResourceAsStream(BUNDLED + ".sig")) {
			if (in == null || sig == null) {
				problems.add("bundled database missing");
			} else {
				bundled = parseVerified(in.readAllBytes(), new String(sig.readAllBytes(), StandardCharsets.UTF_8), DatabaseSignature.PUBLIC_KEY, "bundled",
						problems);
			}
		} catch (IOException e) {
			problems.add("bundled database unreadable: " + e.getMessage());
		}
		Data remote = null;
		if (downloaded != null && Files.exists(downloaded)) {
			try {
				Path sig = downloaded.resolveSibling(downloaded.getFileName() + ".sig");
				remote = parseVerified(Files.readAllBytes(downloaded), Files.exists(sig) ? Files.readString(sig) : "", DatabaseSignature.PUBLIC_KEY, "downloaded",
						problems);
			} catch (IOException e) {
				problems.add("downloaded database unreadable: " + e.getMessage());
			}
		}
		return choose(bundled, remote, problems);
	}

	static CompatibilityDatabase choose(@Nullable Data bundled, @Nullable Data downloaded, List<String> problems) {
		if (downloaded != null && (bundled == null || downloaded.sequence > bundled.sequence)) {
			return new CompatibilityDatabase(downloaded, "downloaded", problems);
		}
		if (downloaded != null) {
			problems.add("downloaded database (sequence " + downloaded.sequence + ") is not newer than the bundled one (" + bundled.sequence + ")");
		}
		return bundled != null ? new CompatibilityDatabase(bundled, "bundled", problems) : new CompatibilityDatabase(null, "none", problems);
	}

	/** Parses {@code bytes} only if the signature is valid for them. */
	static @Nullable Data parseVerified(byte[] bytes, String signature, String publicKey, String what, List<String> problems) {
		if (signature.isBlank() || !DatabaseSignature.verify(bytes, signature, publicKey)) {
			problems.add(what + " database rejected: invalid or missing signature (sha256 " + DatabaseSignature.sha256(bytes) + ")");
			return null;
		}
		try {
			Data data = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Data.class);
			if (data == null || data.schema != SCHEMA || data.entries == null) {
				problems.add(what + " database rejected: unsupported schema");
				return null;
			}
			data.entries.removeIf(e -> e == null || e.modId == null || e.status == null);
			return data;
		} catch (JsonParseException e) {
			problems.add(what + " database rejected: " + e.getMessage());
			return null;
		}
	}

	/** First entry matching the mod, its version, the loader and Minecraft. */
	public Optional<Entry> find(String modId, String version, String loader, String minecraft) {
		if (data == null) {
			return Optional.empty();
		}
		return data.entries.stream().filter(e -> e.matches(modId, version, loader, minecraft)).findFirst();
	}

	public String source() {
		return source;
	}

	public long sequence() {
		return data == null ? -1 : data.sequence;
	}

	public int size() {
		return data == null ? 0 : data.entries.size();
	}

	public List<String> problems() {
		return problems;
	}
}
