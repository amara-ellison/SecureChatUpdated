package dev.securechatupdated;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.interfaces.MLKEMPrivateKey;
import org.bouncycastle.jcajce.interfaces.MLKEMPublicKey;
import org.bouncycastle.jcajce.interfaces.MLDSAPrivateKey;
import org.bouncycastle.jcajce.interfaces.MLDSAPublicKey;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;
import org.bouncycastle.jcajce.spec.MLKEMPrivateKeySpec;
import org.bouncycastle.jcajce.spec.MLKEMPublicKeySpec;
import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.bouncycastle.jcajce.spec.MLDSAPrivateKeySpec;
import org.bouncycastle.jcajce.spec.MLDSAPublicKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.KeyGenerator;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class SecureChatUpdatedCryptoUtil {
    public static final String PROTOCOL_VERSION = "securechatupdated-no-mls-paranoid-v1";
    public static final int KEY_SIZE = 32;
    public static final int XCHACHA_NONCE_SIZE = 24;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Base64.Encoder B64_ENCODER = Base64.getUrlEncoder();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private SecureChatUpdatedCryptoUtil() {}

    public static long nowMs() {
        return System.currentTimeMillis();
    }

    public static String b64e(byte[] data) {
        return B64_ENCODER.encodeToString(data);
    }

    public static byte[] b64d(String data) {
        return B64_DECODER.decode(data);
    }

    public static byte[] canonicalBytes(JsonElement element) {
        return canonical(element).getBytes(StandardCharsets.UTF_8);
    }

    public static String canonical(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonObject()) {
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), entry.getValue());
            }
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(GSON.toJson(new JsonPrimitive(entry.getKey())));
                out.append(':');
                out.append(canonical(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (element.isJsonArray()) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (JsonElement child : element.getAsJsonArray()) {
                if (!first) out.append(',');
                first = false;
                out.append(canonical(child));
            }
            return out.append(']').toString();
        }
        return GSON.toJson(element);
    }

    public static JsonObject parseObject(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    public static XChaChaBox xchachaEncrypt(byte[] key, byte[] plaintext, byte[] aad) throws InvalidCipherTextException {
        byte[] nonce = new byte[XCHACHA_NONCE_SIZE];
        RNG.nextBytes(nonce);
        byte[] ciphertext = xchachaCrypt(true, key, nonce, plaintext, aad);
        return new XChaChaBox(b64e(nonce), b64e(ciphertext));
    }

    public static byte[] xchachaDecrypt(byte[] key, String nonceB64, String ciphertextB64, byte[] aad) throws InvalidCipherTextException {
        return xchachaCrypt(false, key, b64d(nonceB64), b64d(ciphertextB64), aad);
    }

    private static byte[] xchachaCrypt(boolean encrypt, byte[] key, byte[] nonce, byte[] input, byte[] aad) throws InvalidCipherTextException {
        if (key.length != KEY_SIZE) {
            throw new InvalidCipherTextException("XChaCha20-Poly1305 key must be 32 bytes");
        }
        if (nonce.length != XCHACHA_NONCE_SIZE) {
            throw new InvalidCipherTextException("XChaCha20-Poly1305 nonce must be 24 bytes");
        }

        byte[] aadBytes = aad == null ? new byte[0] : aad;
        byte[] subKey = hChaCha20(key, Arrays.copyOfRange(nonce, 0, 16));
        byte[] ietfNonce = new byte[12];
        System.arraycopy(nonce, 16, ietfNonce, 4, 8);
        byte[] polyKey = Arrays.copyOf(chacha20Xor(subKey, ietfNonce, 0, new byte[64]), 32);

        if (encrypt) {
            byte[] ciphertext = chacha20Xor(subKey, ietfNonce, 1, input);
            byte[] tag = poly1305AeadTag(polyKey, aadBytes, ciphertext);
            return concat(ciphertext, tag);
        }

        if (input.length < 16) {
            throw new InvalidCipherTextException("ciphertext too short");
        }
        byte[] ciphertext = Arrays.copyOf(input, input.length - 16);
        byte[] receivedTag = Arrays.copyOfRange(input, input.length - 16, input.length);
        byte[] expectedTag = poly1305AeadTag(polyKey, aadBytes, ciphertext);
        if (!MessageDigest.isEqual(receivedTag, expectedTag)) {
            throw new InvalidCipherTextException("bad XChaCha20-Poly1305 tag");
        }
        return chacha20Xor(subKey, ietfNonce, 1, ciphertext);
    }

    private static byte[] hChaCha20(byte[] key, byte[] nonce16) {
        int[] state = chachaInitialState(key, 0, nonce16, true);
        chachaRounds(state);

        byte[] out = new byte[32];
        intToLittleEndian(state[0], out, 0);
        intToLittleEndian(state[1], out, 4);
        intToLittleEndian(state[2], out, 8);
        intToLittleEndian(state[3], out, 12);
        intToLittleEndian(state[12], out, 16);
        intToLittleEndian(state[13], out, 20);
        intToLittleEndian(state[14], out, 24);
        intToLittleEndian(state[15], out, 28);
        return out;
    }

    private static byte[] chacha20Xor(byte[] key, byte[] nonce12, int initialCounter, byte[] input) {
        byte[] output = new byte[input.length];
        int offset = 0;
        int counter = initialCounter;
        while (offset < input.length) {
            byte[] block = chacha20Block(key, nonce12, counter++);
            int length = Math.min(64, input.length - offset);
            for (int i = 0; i < length; i++) {
                output[offset + i] = (byte) (input[offset + i] ^ block[i]);
            }
            offset += length;
        }
        return output;
    }

    private static byte[] chacha20Block(byte[] key, byte[] nonce12, int counter) {
        int[] state = chachaInitialState(key, counter, nonce12, false);
        int[] working = state.clone();
        chachaRounds(working);

        byte[] out = new byte[64];
        for (int i = 0; i < 16; i++) {
            intToLittleEndian(working[i] + state[i], out, i * 4);
        }
        return out;
    }

    private static int[] chachaInitialState(byte[] key, int counter, byte[] nonce, boolean hchacha) {
        int[] state = new int[16];
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) {
            state[4 + i] = littleEndianToInt(key, i * 4);
        }
        if (hchacha) {
            for (int i = 0; i < 4; i++) {
                state[12 + i] = littleEndianToInt(nonce, i * 4);
            }
        } else {
            state[12] = counter;
            state[13] = littleEndianToInt(nonce, 0);
            state[14] = littleEndianToInt(nonce, 4);
            state[15] = littleEndianToInt(nonce, 8);
        }
        return state;
    }

    private static void chachaRounds(int[] state) {
        for (int i = 0; i < 10; i++) {
            quarterRound(state, 0, 4, 8, 12);
            quarterRound(state, 1, 5, 9, 13);
            quarterRound(state, 2, 6, 10, 14);
            quarterRound(state, 3, 7, 11, 15);
            quarterRound(state, 0, 5, 10, 15);
            quarterRound(state, 1, 6, 11, 12);
            quarterRound(state, 2, 7, 8, 13);
            quarterRound(state, 3, 4, 9, 14);
        }
    }

    private static void quarterRound(int[] state, int a, int b, int c, int d) {
        state[a] += state[b];
        state[d] = Integer.rotateLeft(state[d] ^ state[a], 16);
        state[c] += state[d];
        state[b] = Integer.rotateLeft(state[b] ^ state[c], 12);
        state[a] += state[b];
        state[d] = Integer.rotateLeft(state[d] ^ state[a], 8);
        state[c] += state[d];
        state[b] = Integer.rotateLeft(state[b] ^ state[c], 7);
    }

    private static byte[] poly1305AeadTag(byte[] polyKey, byte[] aad, byte[] ciphertext) {
        byte[] macData = concat(
                aad,
                padding16(aad.length),
                ciphertext,
                padding16(ciphertext.length),
                longToLittleEndian(aad.length),
                longToLittleEndian(ciphertext.length)
        );
        return poly1305(polyKey, macData);
    }

    private static byte[] poly1305(byte[] key, byte[] message) {
        byte[] rBytes = Arrays.copyOfRange(key, 0, 16);
        rBytes[3] &= 15;
        rBytes[7] &= 15;
        rBytes[11] &= 15;
        rBytes[15] &= 15;
        rBytes[4] &= 252;
        rBytes[8] &= 252;
        rBytes[12] &= 252;

        BigInteger r = littleEndianToBigInteger(rBytes);
        BigInteger s = littleEndianToBigInteger(Arrays.copyOfRange(key, 16, 32));
        BigInteger p = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
        BigInteger acc = BigInteger.ZERO;

        for (int offset = 0; offset < message.length; offset += 16) {
            int length = Math.min(16, message.length - offset);
            byte[] block = new byte[length + 1];
            System.arraycopy(message, offset, block, 0, length);
            block[length] = 1;
            acc = acc.add(littleEndianToBigInteger(block)).multiply(r).mod(p);
        }

        BigInteger tag = acc.add(s).mod(BigInteger.ONE.shiftLeft(128));
        return bigIntegerToLittleEndian(tag, 16);
    }

    private static byte[] padding16(int length) {
        int remainder = length % 16;
        return remainder == 0 ? new byte[0] : new byte[16 - remainder];
    }

    private static int littleEndianToInt(byte[] input, int offset) {
        return (input[offset] & 0xFF)
                | ((input[offset + 1] & 0xFF) << 8)
                | ((input[offset + 2] & 0xFF) << 16)
                | ((input[offset + 3] & 0xFF) << 24);
    }

    private static void intToLittleEndian(int value, byte[] output, int offset) {
        output[offset] = (byte) value;
        output[offset + 1] = (byte) (value >>> 8);
        output[offset + 2] = (byte) (value >>> 16);
        output[offset + 3] = (byte) (value >>> 24);
    }

    private static byte[] longToLittleEndian(long value) {
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (value >>> (8 * i));
        }
        return out;
    }

    private static BigInteger littleEndianToBigInteger(byte[] input) {
        byte[] reversed = input.clone();
        reverse(reversed);
        return new BigInteger(1, reversed);
    }

    private static byte[] bigIntegerToLittleEndian(BigInteger value, int size) {
        byte[] bigEndian = value.toByteArray();
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            int source = bigEndian.length - 1 - i;
            out[i] = source >= 0 ? bigEndian[source] : 0;
        }
        return out;
    }

    private static void reverse(byte[] input) {
        for (int i = 0, j = input.length - 1; i < j; i++, j--) {
            byte tmp = input[i];
            input[i] = input[j];
            input[j] = tmp;
        }
    }

    public static byte[] hkdfSha512(byte[] secret, byte[] salt, byte[] info, int length) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
        mac.init(new javax.crypto.spec.SecretKeySpec(salt, "HmacSHA512"));
        byte[] prk = mac.doFinal(secret);

        byte[] okm = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < length) {
            mac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA512"));
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter);
            previous = mac.doFinal();
            int copy = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, okm, offset, copy);
            offset += copy;
            counter++;
        }
        return okm;
    }

    public static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    public static String fingerprintBundle(JsonObject bundle) throws Exception {
        List<byte[]> pieces = new ArrayList<>();
        pieces.add("SECURECHATUPDATED-FINGERPRINT-V1".getBytes(StandardCharsets.UTF_8));
        pieces.add(b64d(string(bundle, "ed25519_public")));
        pieces.add(b64d(string(bundle, "ml_dsa_87_public")));
        pieces.add(b64d(string(bundle, "x25519_public")));
        pieces.add(b64d(string(bundle, "ml_kem_1024_public")));

        int size = Math.max(0, pieces.size() - 1);
        for (byte[] piece : pieces) {
            size += piece.length;
        }
        byte[] material = new byte[size];
        int offset = 0;
        for (int i = 0; i < pieces.size(); i++) {
            if (i > 0) {
                material[offset++] = (byte) '|';
            }
            byte[] piece = pieces.get(i);
            System.arraycopy(piece, 0, material, offset, piece.length);
            offset += piece.length;
        }

        String hex = sha256Hex(material).toUpperCase(Locale.ROOT);
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < 40; i += 4) {
            groups.add(hex.substring(i, i + 4));
        }
        return String.join("-", groups);
    }

    public static JsonObject stripPacketSignatures(JsonObject packet) {
        JsonObject clone = packet.deepCopy();
        clone.remove("signature_ed25519");
        clone.remove("signature_ml_dsa_87");
        return clone;
    }

    public static JsonObject messageAad(JsonObject header) {
        JsonObject aad = new JsonObject();
        aad.addProperty("protocol", PROTOCOL_VERSION);
        aad.addProperty("type", "secure_message");
        aad.add("message_id", header.get("message_id"));
        aad.add("timestamp_ms", header.get("timestamp_ms"));
        aad.addProperty("sender_fingerprint", string(header.getAsJsonObject("sender_bundle"), "fingerprint"));
        aad.add("recipient_fingerprints", header.getAsJsonArray("recipient_fingerprints"));
        aad.add("cipher", header.get("cipher"));
        aad.add("kdf", header.get("kdf"));
        aad.add("kem", header.get("kem"));
        aad.add("signatures", header.get("signatures"));
        return aad;
    }

    public static Identity createIdentity(String displayName) throws Exception {
        Ed25519PrivateKeyParameters edPriv = new Ed25519PrivateKeyParameters(RNG);
        byte[] edSeed = edPriv.getEncoded();
        byte[] edPub = edPriv.generatePublicKey().getEncoded();

        X25519PrivateKeyParameters xPriv = new X25519PrivateKeyParameters(RNG);
        byte[] xSeed = xPriv.getEncoded();
        byte[] xPub = xPriv.generatePublicKey().getEncoded();

        KeyPairGenerator mlKemGenerator = KeyPairGenerator.getInstance("ML-KEM", "BC");
        mlKemGenerator.initialize(MLKEMParameterSpec.ml_kem_1024, RNG);
        KeyPair mlKemPair = mlKemGenerator.generateKeyPair();

        KeyPairGenerator mlDsaGenerator = KeyPairGenerator.getInstance("ML-DSA", "BC");
        mlDsaGenerator.initialize(MLDSAParameterSpec.ml_dsa_87, RNG);
        KeyPair mlDsaPair = mlDsaGenerator.generateKeyPair();

        return new Identity(
                displayName,
                edSeed,
                edPub,
                xSeed,
                xPub,
                ((MLKEMPublicKey) mlKemPair.getPublic()).getPublicData(),
                ((MLKEMPrivateKey) mlKemPair.getPrivate()).getPrivateData(),
                ((MLDSAPublicKey) mlDsaPair.getPublic()).getPublicData(),
                ((MLDSAPrivateKey) mlDsaPair.getPrivate()).getPrivateData()
        );
    }

    public static Identity identityFromPlaintext(String displayName, JsonObject data) {
        return new Identity(
                displayName,
                b64d(string(data, "ed25519_private")),
                null,
                b64d(string(data, "x25519_private")),
                null,
                b64d(string(data, "ml_kem_1024_public")),
                b64d(string(data, "ml_kem_1024_private")),
                b64d(string(data, "ml_dsa_87_public")),
                b64d(string(data, "ml_dsa_87_private"))
        ).withGeneratedClassicalPublicKeys();
    }

    public static PublicKey mlKemPublicKey(byte[] publicData) throws Exception {
        return KeyFactory.getInstance("ML-KEM", "BC")
                .generatePublic(new MLKEMPublicKeySpec(MLKEMParameterSpec.ml_kem_1024, publicData));
    }

    public static PrivateKey mlKemPrivateKey(byte[] privateData, byte[] publicData) throws Exception {
        return KeyFactory.getInstance("ML-KEM", "BC")
                .generatePrivate(new MLKEMPrivateKeySpec(MLKEMParameterSpec.ml_kem_1024, privateData, publicData));
    }

    public static PublicKey mlDsaPublicKey(byte[] publicData) throws Exception {
        return KeyFactory.getInstance("ML-DSA", "BC")
                .generatePublic(new MLDSAPublicKeySpec(MLDSAParameterSpec.ml_dsa_87, publicData));
    }

    public static PrivateKey mlDsaPrivateKey(byte[] privateData, byte[] publicData) throws Exception {
        return KeyFactory.getInstance("ML-DSA", "BC")
                .generatePrivate(new MLDSAPrivateKeySpec(MLDSAParameterSpec.ml_dsa_87, privateData, publicData));
    }

    public static String string(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    public static long longValue(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? 0L : value.getAsLong();
    }

    public record XChaChaBox(String nonce, String ciphertext) {}

    public record Verification(boolean ok, String reason, JsonObject bundle) {}

    public static final class Identity {
        private String displayName;
        private final byte[] edPrivate;
        private final byte[] edPublic;
        private final byte[] xPrivate;
        private final byte[] xPublic;
        private final byte[] mlKemPublic;
        private final byte[] mlKemPrivate;
        private final byte[] mlDsaPublic;
        private final byte[] mlDsaPrivate;

        private Identity(
                String displayName,
                byte[] edPrivate,
                byte[] edPublic,
                byte[] xPrivate,
                byte[] xPublic,
                byte[] mlKemPublic,
                byte[] mlKemPrivate,
                byte[] mlDsaPublic,
                byte[] mlDsaPrivate
        ) {
            this.displayName = displayName;
            this.edPrivate = edPrivate;
            this.edPublic = edPublic;
            this.xPrivate = xPrivate;
            this.xPublic = xPublic;
            this.mlKemPublic = mlKemPublic;
            this.mlKemPrivate = mlKemPrivate;
            this.mlDsaPublic = mlDsaPublic;
            this.mlDsaPrivate = mlDsaPrivate;
        }

        private Identity withGeneratedClassicalPublicKeys() {
            Ed25519PrivateKeyParameters edPriv = new Ed25519PrivateKeyParameters(edPrivate, 0);
            X25519PrivateKeyParameters xPriv = new X25519PrivateKeyParameters(xPrivate, 0);
            return new Identity(
                    displayName,
                    edPrivate,
                    edPriv.generatePublicKey().getEncoded(),
                    xPrivate,
                    xPriv.generatePublicKey().getEncoded(),
                    mlKemPublic,
                    mlKemPrivate,
                    mlDsaPublic,
                    mlDsaPrivate
            );
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public JsonObject publicBundle() throws Exception {
            JsonObject bundle = new JsonObject();
            bundle.addProperty("display_name", displayName);
            bundle.addProperty("ed25519_public", b64e(edPublic));
            bundle.addProperty("ml_dsa_87_public", b64e(mlDsaPublic));
            bundle.addProperty("x25519_public", b64e(xPublic));
            bundle.addProperty("ml_kem_1024_public", b64e(mlKemPublic));
            bundle.addProperty("fingerprint", fingerprintBundle(bundle));
            return bundle;
        }

        public String fingerprint() throws Exception {
            return string(publicBundle(), "fingerprint");
        }

        public JsonObject privatePlaintext() {
            JsonObject data = new JsonObject();
            data.addProperty("ed25519_private", b64e(edPrivate));
            data.addProperty("x25519_private", b64e(xPrivate));
            data.addProperty("ml_kem_1024_public", b64e(mlKemPublic));
            data.addProperty("ml_kem_1024_private", b64e(mlKemPrivate));
            data.addProperty("ml_dsa_87_public", b64e(mlDsaPublic));
            data.addProperty("ml_dsa_87_private", b64e(mlDsaPrivate));
            return data;
        }

        public JsonObject signPacket(JsonObject packet) throws Exception {
            byte[] data = canonicalBytes(stripPacketSignatures(packet));

            Ed25519Signer edSigner = new Ed25519Signer();
            edSigner.init(true, new Ed25519PrivateKeyParameters(edPrivate, 0));
            edSigner.update(data, 0, data.length);
            packet.addProperty("signature_ed25519", b64e(edSigner.generateSignature()));

            Signature mlDsaSigner = Signature.getInstance("ML-DSA-87", "BC");
            mlDsaSigner.initSign(mlDsaPrivateKey(mlDsaPrivate, mlDsaPublic), RNG);
            mlDsaSigner.update(data);
            packet.addProperty("signature_ml_dsa_87", b64e(mlDsaSigner.sign()));
            return packet;
        }

        public JsonObject wrapMessageKey(byte[] messageKey, JsonObject recipient, String messageId) throws Exception {
            JsonObject bundle = recipient.getAsJsonObject("bundle");
            String recipientFp = string(bundle, "fingerprint");

            KeyGenerator kemGenerator = KeyGenerator.getInstance("ML-KEM", "BC");
            kemGenerator.init(new KEMGenerateSpec(mlKemPublicKey(b64d(string(bundle, "ml_kem_1024_public"))), "AES"), RNG);
            SecretKeyWithEncapsulation encapsulated = (SecretKeyWithEncapsulation) kemGenerator.generateKey();
            byte[] mlKemCiphertext = encapsulated.getEncapsulation();
            byte[] mlKemSecret = encapsulated.getEncoded();

            X25519PrivateKeyParameters ephPriv = new X25519PrivateKeyParameters(RNG);
            byte[] ephPub = ephPriv.generatePublicKey().getEncoded();
            byte[] x25519Secret = new byte[KEY_SIZE];
            ephPriv.generateSecret(new X25519PublicKeyParameters(b64d(string(bundle, "x25519_public")), 0), x25519Secret, 0);

            byte[] salt = sha512(concat(
                    "SECURECHATUPDATED-HYBRID-WRAP-SALT-V1|".getBytes(StandardCharsets.UTF_8),
                    messageId.getBytes(StandardCharsets.UTF_8),
                    fingerprint().getBytes(StandardCharsets.UTF_8),
                    recipientFp.getBytes(StandardCharsets.UTF_8),
                    mlKemCiphertext,
                    ephPub
            ));
            byte[] wrapKey = hkdfSha512(concat(x25519Secret, mlKemSecret), salt,
                    "SECURECHATUPDATED-HYBRID-WRAP-KEY-V1".getBytes(StandardCharsets.UTF_8), KEY_SIZE);

            JsonObject aad = new JsonObject();
            aad.addProperty("protocol", PROTOCOL_VERSION);
            aad.addProperty("message_id", messageId);
            aad.addProperty("sender_fingerprint", fingerprint());
            aad.addProperty("recipient_fingerprint", recipientFp);

            XChaChaBox wrapped = xchachaEncrypt(wrapKey, messageKey, canonicalBytes(aad));
            JsonObject out = new JsonObject();
            out.addProperty("ml_kem_1024_ciphertext", b64e(mlKemCiphertext));
            out.addProperty("x25519_ephemeral_public", b64e(ephPub));
            out.addProperty("wrap_nonce", wrapped.nonce());
            out.addProperty("wrapped_message_key", wrapped.ciphertext());
            return out;
        }

        public byte[] unwrapMessageKey(JsonObject packet) {
            try {
                String ownFp = fingerprint();
                JsonObject wrappedKeys = packet.getAsJsonObject("wrapped_keys");
                JsonObject wrapped = wrappedKeys == null ? null : wrappedKeys.getAsJsonObject(ownFp);
                if (wrapped == null) {
                    return null;
                }

                byte[] mlKemCiphertext = b64d(string(wrapped, "ml_kem_1024_ciphertext"));
                KeyGenerator kemExtractor = KeyGenerator.getInstance("ML-KEM", "BC");
                kemExtractor.init(new KEMExtractSpec(mlKemPrivateKey(mlKemPrivate, mlKemPublic), mlKemCiphertext, "AES"));
                byte[] mlKemSecret = ((SecretKeyWithEncapsulation) kemExtractor.generateKey()).getEncoded();

                byte[] ephPub = b64d(string(wrapped, "x25519_ephemeral_public"));
                byte[] x25519Secret = new byte[KEY_SIZE];
                new X25519PrivateKeyParameters(xPrivate, 0)
                        .generateSecret(new X25519PublicKeyParameters(ephPub, 0), x25519Secret, 0);

                String senderFp = string(packet.getAsJsonObject("sender_bundle"), "fingerprint");
                byte[] salt = sha512(concat(
                        "SECURECHATUPDATED-HYBRID-WRAP-SALT-V1|".getBytes(StandardCharsets.UTF_8),
                        string(packet, "message_id").getBytes(StandardCharsets.UTF_8),
                        senderFp.getBytes(StandardCharsets.UTF_8),
                        ownFp.getBytes(StandardCharsets.UTF_8),
                        mlKemCiphertext,
                        ephPub
                ));
                byte[] wrapKey = hkdfSha512(concat(x25519Secret, mlKemSecret), salt,
                        "SECURECHATUPDATED-HYBRID-WRAP-KEY-V1".getBytes(StandardCharsets.UTF_8), KEY_SIZE);

                JsonObject aad = new JsonObject();
                aad.addProperty("protocol", PROTOCOL_VERSION);
                aad.addProperty("message_id", string(packet, "message_id"));
                aad.addProperty("sender_fingerprint", senderFp);
                aad.addProperty("recipient_fingerprint", ownFp);

                return xchachaDecrypt(wrapKey, string(wrapped, "wrap_nonce"),
                        string(wrapped, "wrapped_message_key"), canonicalBytes(aad));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public static Verification verifySignedPacket(JsonObject packet) {
        JsonObject bundle = packet == null ? null : packet.getAsJsonObject("sender_bundle");
        if (bundle == null) {
            return new Verification(false, "missing sender bundle", new JsonObject());
        }

        try {
            String expectedFp = fingerprintBundle(bundle);
            if (!expectedFp.equals(string(bundle, "fingerprint"))) {
                return new Verification(false, "fingerprint mismatch", bundle);
            }

            byte[] signedData = canonicalBytes(stripPacketSignatures(packet));

            Ed25519Signer edVerifier = new Ed25519Signer();
            edVerifier.init(false, new Ed25519PublicKeyParameters(b64d(string(bundle, "ed25519_public")), 0));
            edVerifier.update(signedData, 0, signedData.length);
            if (!edVerifier.verifySignature(b64d(string(packet, "signature_ed25519")))) {
                return new Verification(false, "bad Ed25519 signature", bundle);
            }

            Signature mlDsaVerifier = Signature.getInstance("ML-DSA-87", "BC");
            mlDsaVerifier.initVerify(mlDsaPublicKey(b64d(string(bundle, "ml_dsa_87_public"))));
            mlDsaVerifier.update(signedData);
            if (!mlDsaVerifier.verify(b64d(string(packet, "signature_ml_dsa_87")))) {
                return new Verification(false, "bad ML-DSA-87 signature", bundle);
            }

            return new Verification(true, "ok", bundle);
        } catch (Exception e) {
            String message = e.getMessage();
            return new Verification(false, "invalid signed packet: " + e.getClass().getSimpleName() + (message == null ? "" : " " + message), bundle);
        }
    }

    public static byte[] sha512(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(data);
    }

    public static byte[] concat(byte[]... chunks) {
        int size = 0;
        for (byte[] chunk : chunks) {
            size += chunk.length;
        }
        byte[] out = new byte[size];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.length);
            offset += chunk.length;
        }
        return out;
    }

    public static JsonArray sortedStringArray(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        JsonArray array = new JsonArray();
        for (String value : copy) {
            array.add(value);
        }
        return array;
    }
}
