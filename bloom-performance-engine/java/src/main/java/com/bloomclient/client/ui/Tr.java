package com.bloomclient.client.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Bloom translations.
 *
 * <p>Without Fabric API, Minecraft does not load assets from mod jars, so
 * Bloom reads its own language files from the classpath
 * ({@code assets/bloom/lang/<code>.json}) and answers lookups for keys
 * starting with {@code bloom.}, {@code key.bloom.} and
 * {@code key.category.bloom.} through {@code ClientLanguageMixin}: the keys
 * work in {@code Component.translatable} and in the controls screen too.
 * English is the fallback for missing keys.
 */
public final class Tr {
	private static final Map<String, String> FALLBACK = load("en_us");
	private static volatile Map<String, String> current = FALLBACK;
	private static volatile String currentCode = "en_us";

	private Tr() {
	}

	public static boolean handles(String key) {
		return key.startsWith("bloom.") || key.startsWith("key.bloom.") || key.startsWith("key.category.bloom.");
	}

	/** Translation for {@code key}, or {@code null} when neither the current language nor English has it. */
	public static @Nullable String lookup(String key) {
		refresh();
		String value = current.get(key);
		return value != null ? value : FALLBACK.get(key);
	}

	public static String t(String key) {
		String value = lookup(key);
		return value != null ? value : key;
	}

	public static String t(String key, Object... args) {
		String value = t(key);
		try {
			return String.format(Locale.ROOT, value, args);
		} catch (RuntimeException e) {
			return value;
		}
	}

	/** Current language code, e.g. {@code it_it}. */
	public static String language() {
		refresh();
		return currentCode;
	}

	private static void refresh() {
		Minecraft mc = Minecraft.getInstance();
		String code = mc == null || mc.options == null ? "en_us" : mc.options.languageCode;
		if (code == null) code = "en_us";
		code = code.toLowerCase(Locale.ROOT);
		if (!code.equals(currentCode)) {
			currentCode = code;
			current = code.equals("en_us") ? FALLBACK : load(code);
		}
	}

	private static Map<String, String> load(String code) {
		Map<String, String> map = new HashMap<>();
		try (InputStream in = Tr.class.getResourceAsStream("/assets/bloom/lang/" + code + ".json")) {
			if (in == null) {
				return map;
			}
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			json.entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsString()));
		} catch (Exception e) {
			com.bloomclient.client.BloomClient.LOGGER.warn("Bloom: cannot read language file {}", code, e);
		}
		return map;
	}
}
