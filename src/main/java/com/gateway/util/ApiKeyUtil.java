package com.gateway.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class ApiKeyUtil {

    private static final String PREFIX = "sk-gw-";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ApiKeyUtil() {}

    public static String generate() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(PREFIX);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return sb.toString();
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String prefixOf(String key) {
        if (key == null || key.length() < 12) {
            return key == null ? "" : key;
        }
        return key.substring(0, 12);
    }

    /** AES(ECB/PKCS5) 加密，密钥由 secret 经 SHA-256 派生。用于可重看明文密钥副本。 */
    public static String encryptKey(String plain, String secret) {
        try {
            byte[] sha = sha256(secret);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sha, "AES"));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("key encryption failed", e);
        }
    }

    public static String decryptKey(String encrypted, String secret) {
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        try {
            byte[] sha = sha256(secret);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sha, "AES"));
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] sha256(String secret) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((secret == null ? "llm-gateway-default" : secret).getBytes(StandardCharsets.UTF_8));
        return Arrays.copyOf(digest, 16);
    }
}
