/*
 * This is the source code of Moagram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.messenger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push decryption for Telegram's {@code token_type = 10} pushes.
 *
 * <p>Telegram encrypts web pushes with the legacy {@code aesgcm} content encoding
 * (draft-ietf-webpush-encryption-04), which carries the salt and the server's
 * ECDH key in HTTP headers. UnifiedPush distributors only forward the body, so
 * Moagram's relay prepends those headers to the body:
 *
 * <pre>
 * aesgcm\n
 * Encryption: salt=&lt;base64url&gt;[;rs=&lt;n&gt;]\n
 * Crypto-Key: dh=&lt;base64url&gt;[;p256ecdsa=...]\n
 * &lt;binary ciphertext&gt;
 * </pre>
 *
 * <p>Plain Java with no Android dependencies, so it can be unit tested on a JVM.
 */
public final class MoagramWebPushCrypto {

    public static final int AUTH_SECRET_LENGTH = 16;
    public static final int PUBLIC_KEY_LENGTH = 65;

    private static final int DEFAULT_RECORD_SIZE = 4096;
    private static final int TAG_LENGTH = 16;
    private static final int MAX_HEADER_BYTES = 1024;
    private static final byte[] GATEWAY_MAGIC = "aesgcm\n".getBytes(StandardCharsets.US_ASCII);

    // DER prefix of a P-256 SubjectPublicKeyInfo; the raw 65-byte point follows.
    private static final byte[] P256_SPKI_PREFIX = hex(
            "3059301306072a8648ce3d020106082a8648ce3d030107034200");

    private MoagramWebPushCrypto() {
    }

    public static final class Keys {
        public final byte[] privateKeyPkcs8;
        public final byte[] publicKeyRaw;
        public final byte[] authSecret;

        public Keys(byte[] privateKeyPkcs8, byte[] publicKeyRaw, byte[] authSecret) {
            this.privateKeyPkcs8 = privateKeyPkcs8;
            this.publicKeyRaw = publicKeyRaw;
            this.authSecret = authSecret;
        }

        public boolean isValid() {
            return privateKeyPkcs8 != null && privateKeyPkcs8.length > 0
                    && publicKeyRaw != null && publicKeyRaw.length == PUBLIC_KEY_LENGTH && publicKeyRaw[0] == 0x04
                    && authSecret != null && authSecret.length == AUTH_SECRET_LENGTH;
        }
    }

    public static Keys generateKeys() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        byte[] auth = new byte[AUTH_SECRET_LENGTH];
        new SecureRandom().nextBytes(auth);
        return new Keys(pair.getPrivate().getEncoded(), rawPublicKey((ECPublicKey) pair.getPublic()), auth);
    }

    /** Uncompressed point 0x04 || X || Y, each coordinate left-padded to 32 bytes. */
    public static byte[] rawPublicKey(ECPublicKey key) {
        byte[] raw = new byte[PUBLIC_KEY_LENGTH];
        raw[0] = 0x04;
        copyUnsigned(key.getW().getAffineX().toByteArray(), raw, 1);
        copyUnsigned(key.getW().getAffineY().toByteArray(), raw, 33);
        return raw;
    }

    // java.util.Base64 needs API 26 and android.util.Base64 is not on a plain JVM,
    // so this class carries its own unpadded base64url codec.
    private static final char[] B64_URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    public static String base64Url(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
        for (int i = 0; i < data.length; i += 3) {
            int n = (data[i] & 0xff) << 16;
            int remaining = data.length - i;
            if (remaining > 1) n |= (data[i + 1] & 0xff) << 8;
            if (remaining > 2) n |= data[i + 2] & 0xff;
            out.append(B64_URL[(n >>> 18) & 63]).append(B64_URL[(n >>> 12) & 63]);
            if (remaining > 1) out.append(B64_URL[(n >>> 6) & 63]);
            if (remaining > 2) out.append(B64_URL[n & 63]);
        }
        return out.toString();
    }

    /** Decodes base64url or standard base64, with or without padding. */
    public static byte[] fromBase64Url(String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length() * 3 / 4);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '-' || c == '+') v = 62;
            else if (c == '_' || c == '/') v = 63;
            else if (c == '=' || Character.isWhitespace(c)) continue;
            else throw new IllegalArgumentException("invalid base64 character");
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >>> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    /** True if the body was framed by Moagram's relay (or a compatible aesgcm gateway). */
    public static boolean isGatewayBody(byte[] body) {
        return body != null && body.length > GATEWAY_MAGIC.length
                && Arrays.equals(Arrays.copyOf(body, GATEWAY_MAGIC.length), GATEWAY_MAGIC);
    }

    /** Decrypts a relay-framed push body and returns the plaintext. */
    public static byte[] decryptGatewayBody(byte[] body, Keys keys) throws GeneralSecurityException {
        if (!isGatewayBody(body)) {
            throw new GeneralSecurityException("not an aesgcm gateway body");
        }
        int offset = GATEWAY_MAGIC.length;
        String encryption = null;
        String cryptoKey = null;
        for (int line = 0; line < 2; line++) {
            int end = indexOf(body, (byte) '\n', offset, Math.min(body.length, offset + MAX_HEADER_BYTES));
            if (end < 0) {
                throw new GeneralSecurityException("truncated gateway header");
            }
            String header = new String(body, offset, end - offset, StandardCharsets.US_ASCII);
            offset = end + 1;
            int colon = header.indexOf(':');
            if (colon <= 0) {
                throw new GeneralSecurityException("malformed gateway header");
            }
            String name = header.substring(0, colon).trim();
            String value = header.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Encryption")) {
                encryption = value;
            } else if (name.equalsIgnoreCase("Crypto-Key")) {
                cryptoKey = value;
            }
        }
        String salt = headerParam(encryption, "salt");
        String dh = headerParam(cryptoKey, "dh");
        if (salt == null || dh == null) {
            throw new GeneralSecurityException("missing salt or dh");
        }
        int recordSize = DEFAULT_RECORD_SIZE;
        String rs = headerParam(encryption, "rs");
        if (rs != null) {
            try {
                recordSize = Integer.parseInt(rs);
            } catch (NumberFormatException e) {
                throw new GeneralSecurityException("bad record size");
            }
        }
        byte[] ciphertext = Arrays.copyOfRange(body, offset, body.length);
        return decryptAesgcm(keys, fromBase64Url(dh), fromBase64Url(salt), recordSize, ciphertext);
    }

    public static byte[] decryptAesgcm(Keys keys, byte[] serverPublicKey, byte[] salt, int recordSize,
                                       byte[] ciphertext) throws GeneralSecurityException {
        if (keys == null || !keys.isValid()) {
            throw new GeneralSecurityException("web push keys are missing");
        }
        if (serverPublicKey.length != PUBLIC_KEY_LENGTH || salt.length != 16) {
            throw new GeneralSecurityException("bad dh or salt length");
        }
        if (recordSize < 3 || ciphertext.length == 0) {
            throw new GeneralSecurityException("bad record size or empty body");
        }

        KeyFactory factory = KeyFactory.getInstance("EC");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(keys.privateKeyPkcs8));
        PublicKey serverKey = factory.generatePublic(new X509EncodedKeySpec(concat(P256_SPKI_PREFIX, serverPublicKey)));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(serverKey, true);
        byte[] sharedSecret = agreement.generateSecret();

        byte[] ikm = hkdf(keys.authSecret, sharedSecret, ascii("Content-Encoding: auth\0"), 32);
        byte[] context = concat(ascii("P-256\0"),
                length16(keys.publicKeyRaw), keys.publicKeyRaw,
                length16(serverPublicKey), serverPublicKey);
        byte[] cek = hkdf(salt, ikm, concat(ascii("Content-Encoding: aesgcm\0"), context), 16);
        byte[] baseNonce = hkdf(salt, ikm, concat(ascii("Content-Encoding: nonce\0"), context), 12);

        int encryptedRecordSize = recordSize + TAG_LENGTH;
        ByteArrayOutputStream plaintext = new ByteArrayOutputStream(ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKeySpec key = new SecretKeySpec(cek, "AES");
        long sequence = 0;
        for (int start = 0; start < ciphertext.length; start += encryptedRecordSize, sequence++) {
            int end = Math.min(ciphertext.length, start + encryptedRecordSize);
            if (end - start <= TAG_LENGTH) {
                throw new GeneralSecurityException("truncated record");
            }
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH * 8, recordNonce(baseNonce, sequence)));
            byte[] record = cipher.doFinal(ciphertext, start, end - start);
            if (record.length < 2) {
                throw new GeneralSecurityException("short record");
            }
            int padding = ((record[0] & 0xff) << 8) | (record[1] & 0xff);
            if (2 + padding > record.length) {
                throw new GeneralSecurityException("bad padding");
            }
            for (int i = 2; i < 2 + padding; i++) {
                if (record[i] != 0) {
                    throw new GeneralSecurityException("non-zero padding");
                }
            }
            plaintext.write(record, 2 + padding, record.length - 2 - padding);
        }
        return plaintext.toByteArray();
    }

    static String headerParam(String header, String name) {
        if (header == null) {
            return null;
        }
        for (String part : header.split("[,;]")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase(name)) {
                String value = part.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    /** HKDF-SHA256 (RFC 5869) for output lengths up to 32 bytes. */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), length);
    }

    private static byte[] recordNonce(byte[] baseNonce, long sequence) {
        byte[] nonce = baseNonce.clone();
        for (int i = 0; i < 6; i++) {
            nonce[nonce.length - 1 - i] ^= (byte) (sequence >>> (8 * i));
        }
        return nonce;
    }

    private static void copyUnsigned(byte[] value, byte[] target, int offset) {
        if (value.length >= 32) {
            System.arraycopy(value, value.length - 32, target, offset, 32);
        } else {
            System.arraycopy(value, 0, target, offset + 32 - value.length, value.length);
        }
    }

    private static int indexOf(byte[] data, byte value, int from, int to) {
        for (int i = from; i < to; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] length16(byte[] data) {
        return new byte[]{(byte) (data.length >>> 8), (byte) data.length};
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
