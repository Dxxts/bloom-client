package com.bloomclient.engine.compatibility;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Integrity of the compatibility database: an Ed25519 signature over the
 * exact bytes of {@code bloom-compat-db.json}, made with Bloom's private key
 * (kept outside the repository) and verified with the public key below.
 * Only the JDK is used.
 */
public final class DatabaseSignature {
	/** Raw 32-byte Ed25519 public key, base64. The same key is in the launcher. */
	public static final String PUBLIC_KEY = "hpPhcERyUXEn8W7tVZyQe3iB9UflpWWhDBBtT7YdwZY=";

	/** DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519 (RFC 8410). */
	private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

	private DatabaseSignature() {
	}

	public static boolean verify(byte[] data, String signatureBase64) {
		return verify(data, signatureBase64, PUBLIC_KEY);
	}

	/** {@code false} for a wrong signature, a malformed one or a malformed key: never throws. */
	public static boolean verify(byte[] data, String signatureBase64, String publicKeyBase64) {
		try {
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(publicKey(Base64.getDecoder().decode(publicKeyBase64.trim())));
			verifier.update(data);
			return verifier.verify(Base64.getDecoder().decode(signatureBase64.trim()));
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			return false;
		}
	}

	static PublicKey publicKey(byte[] raw) throws GeneralSecurityException {
		if (raw.length != 32) {
			throw new GeneralSecurityException("Ed25519 public keys are 32 bytes");
		}
		byte[] der = new byte[X509_PREFIX.length + 32];
		System.arraycopy(X509_PREFIX, 0, der, 0, X509_PREFIX.length);
		System.arraycopy(raw, 0, der, X509_PREFIX.length, 32);
		return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
	}

	public static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}
}
