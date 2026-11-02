package com.bloomclient.engine.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CompatibilityDatabaseTest {
	private static KeyPair keys;
	private static String publicKey;

	@BeforeAll
	static void keys() throws Exception {
		keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		byte[] x509 = keys.getPublic().getEncoded();
		publicKey = Base64.getEncoder().encodeToString(Arrays.copyOfRange(x509, x509.length - 32, x509.length));
	}

	private static String sign(byte[] data) throws Exception {
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(keys.getPrivate());
		signer.update(data);
		return Base64.getEncoder().encodeToString(signer.sign());
	}

	private static byte[] db(long sequence, String entries) {
		return ("{\"schema\":1,\"sequence\":" + sequence + ",\"entries\":[" + entries + "]}").getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void signatureCoversEveryByte() throws Exception {
		byte[] data = db(3, "");
		String sig = sign(data);
		assertTrue(DatabaseSignature.verify(data, sig, publicKey));
		byte[] tampered = data.clone();
		tampered[tampered.length - 2] ^= 1;
		assertFalse(DatabaseSignature.verify(tampered, sig, publicKey));
		// Malformed input never throws.
		assertFalse(DatabaseSignature.verify(data, "not base64!", publicKey));
		assertFalse(DatabaseSignature.verify(data, sig, "AAAA"));
	}

	@Test
	void unsignedOrForeignDatabasesAreRejected() throws Exception {
		List<String> problems = new ArrayList<>();
		byte[] data = db(5, "");
		assertNull(CompatibilityDatabase.parseVerified(data, "", publicKey, "downloaded", problems));
		assertNull(CompatibilityDatabase.parseVerified(data, sign(data), DatabaseSignature.PUBLIC_KEY, "downloaded", problems));
		assertEquals(2, problems.size());
		assertTrue(problems.get(0).contains("invalid or missing signature"));
		assertNotNull(CompatibilityDatabase.parseVerified(data, sign(data), publicKey, "downloaded", new ArrayList<>()));
	}

	@Test
	void unsupportedSchemaIsRejectedEvenWhenSigned() throws Exception {
		byte[] data = "{\"schema\":99,\"sequence\":1,\"entries\":[]}".getBytes(StandardCharsets.UTF_8);
		List<String> problems = new ArrayList<>();
		assertNull(CompatibilityDatabase.parseVerified(data, sign(data), publicKey, "downloaded", problems));
		assertTrue(problems.getFirst().contains("schema"));
	}

	@Test
	void newestValidCopyWinsAndRollbacksAreRefused() throws Exception {
		List<String> p = new ArrayList<>();
		CompatibilityDatabase.Data bundled = CompatibilityDatabase.parseVerified(db(2, ""), sign(db(2, "")), publicKey, "bundled", p);
		CompatibilityDatabase.Data newer = CompatibilityDatabase.parseVerified(db(3, ""), sign(db(3, "")), publicKey, "downloaded", p);
		CompatibilityDatabase.Data older = CompatibilityDatabase.parseVerified(db(1, ""), sign(db(1, "")), publicKey, "downloaded", p);
		assertEquals("downloaded", CompatibilityDatabase.choose(bundled, newer, new ArrayList<>()).source());
		List<String> problems = new ArrayList<>();
		CompatibilityDatabase chosen = CompatibilityDatabase.choose(bundled, older, problems);
		assertEquals("bundled", chosen.source());
		assertEquals(2, chosen.sequence());
		assertTrue(problems.getFirst().contains("not newer"));
		assertEquals("none", CompatibilityDatabase.choose(null, null, new ArrayList<>()).source());
	}

	@Test
	void entriesMatchModVersionLoaderAndMinecraft() throws Exception {
		String entries = "{\"modId\":\"fancy\",\"versions\":\">=2.0.0 <3.0.0\",\"loader\":\"fabric\",\"minecraft\":\"26.3\",\"status\":\"JAVA_FALLBACK\"},"
				+ "{\"modId\":\"fancy\",\"versions\":\"*\",\"loader\":\"fabric\",\"minecraft\":\"*\",\"status\":\"UNTESTED\"}";
		byte[] data = db(1, entries);
		CompatibilityDatabase database = CompatibilityDatabase.choose(CompatibilityDatabase.parseVerified(data, sign(data), publicKey, "bundled",
				new ArrayList<>()), null, new ArrayList<>());
		assertEquals(CompatibilityDatabase.Status.JAVA_FALLBACK, database.find("fancy", "2.4.1", "fabric", "26.3").orElseThrow().status);
		assertEquals(CompatibilityDatabase.Status.UNTESTED, database.find("fancy", "3.0.0", "fabric", "26.3").orElseThrow().status);
		assertEquals(CompatibilityDatabase.Status.UNTESTED, database.find("fancy", "2.4.1", "fabric", "26.4").orElseThrow().status);
		assertTrue(database.find("fancy", "2.4.1", "neoforge", "26.3").isEmpty());
		assertTrue(database.find("other", "1.0.0", "fabric", "26.3").isEmpty());
	}

	@Test
	void bundledDatabaseIsSignedWithTheReleaseKey() {
		// Catches end-of-line conversion or edits made without re-signing.
		CompatibilityDatabase database = CompatibilityDatabase.load(null);
		assertEquals("bundled", database.source(), String.join("; ", database.problems()));
		assertTrue(database.size() > 0);
		assertTrue(database.problems().isEmpty(), String.join("; ", database.problems()));
	}
}
