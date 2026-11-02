import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signs the Bloom compatibility database (JDK only, run with {@code java CompatDbTool.java}).
 *
 * <pre>
 *   java CompatDbTool.java keygen D:\bloom-build\keys\compat-db-ed25519.key
 *   java CompatDbTool.java sign   D:\bloom-build\keys\compat-db-ed25519.key ..\compat-db\bloom-compat-db.json
 *   java CompatDbTool.java verify &lt;public key base64&gt; ..\compat-db\bloom-compat-db.json
 * </pre>
 *
 * {@code sign} writes {@code bloom-compat-db.json.sig} (Ed25519 over the exact
 * file bytes, base64) and {@code index.json} (sequence, size, SHA-256,
 * signature), which is what the launcher downloads first. The private key
 * must never be committed.
 */
public class CompatDbTool {
	private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
	private static final Pattern SEQUENCE = Pattern.compile("\"sequence\"\\s*:\\s*(\\d+)");
	private static final Pattern SCHEMA = Pattern.compile("\"schema\"\\s*:\\s*(\\d+)");

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: keygen <key> | sign <key> <db.json> | verify <publicKeyBase64> <db.json>");
			System.exit(2);
		}
		switch (args[0]) {
			case "keygen" -> keygen(Path.of(args[1]));
			case "sign" -> sign(Path.of(args[1]), Path.of(args[2]));
			case "verify" -> System.exit(verify(args[1], Path.of(args[2])) ? 0 : 1);
			default -> {
				System.err.println("unknown command " + args[0]);
				System.exit(2);
			}
		}
	}

	static void keygen(Path keyFile) throws Exception {
		if (Files.exists(keyFile)) {
			throw new IllegalStateException(keyFile + " exists: refusing to overwrite a signing key");
		}
		KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		Files.createDirectories(keyFile.toAbsolutePath().getParent());
		Files.writeString(keyFile, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
		System.out.println("private key: " + keyFile);
		System.out.println("public key (raw, base64): " + rawPublic(pair.getPublic().getEncoded()));
	}

	static void sign(Path keyFile, Path db) throws Exception {
		byte[] data = Files.readAllBytes(db);
		String text = new String(data, StandardCharsets.UTF_8);
		Matcher schema = SCHEMA.matcher(text);
		Matcher sequence = SEQUENCE.matcher(text);
		if (!schema.find() || !sequence.find()) {
			throw new IllegalArgumentException(db + " has no \"schema\" or \"sequence\" field");
		}
		PrivateKey key = KeyFactory.getInstance("Ed25519")
				.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(keyFile).trim())));
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(key);
		signer.update(data);
		String signature = Base64.getEncoder().encodeToString(signer.sign());
		Files.writeString(db.resolveSibling(db.getFileName() + ".sig"), signature + "\n");
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		String index = "{\n"
				+ "  \"schema\": " + schema.group(1) + ",\n"
				+ "  \"sequence\": " + sequence.group(1) + ",\n"
				+ "  \"file\": \"" + db.getFileName() + "\",\n"
				+ "  \"size\": " + data.length + ",\n"
				+ "  \"sha256\": \"" + sha + "\",\n"
				+ "  \"signature\": \"" + signature + "\"\n"
				+ "}\n";
		Files.writeString(db.resolveSibling("index.json"), index);
		System.out.println("signed " + db + " (sequence " + sequence.group(1) + ", sha256 " + sha + ")");
	}

	static boolean verify(String publicKeyBase64, Path db) throws Exception {
		byte[] raw = Base64.getDecoder().decode(publicKeyBase64);
		byte[] der = Arrays.copyOf(X509_PREFIX, X509_PREFIX.length + 32);
		System.arraycopy(raw, 0, der, X509_PREFIX.length, 32);
		Signature verifier = Signature.getInstance("Ed25519");
		verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
		verifier.update(Files.readAllBytes(db));
		boolean ok = verifier.verify(Base64.getDecoder().decode(Files.readString(db.resolveSibling(db.getFileName() + ".sig")).trim()));
		System.out.println(ok ? "signature OK" : "signature INVALID");
		return ok;
	}

	private static String rawPublic(byte[] x509) {
		return Base64.getEncoder().encodeToString(Arrays.copyOfRange(x509, x509.length - 32, x509.length));
	}
}
