package com.foxcash.domain.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Implementação de TOTP (RFC 6238) — SHA-1, 6 dígitos, passo de 30s — sem
 * dependência externa (Spec 44). O algoritmo é simples (~30 linhas com
 * {@code Mac}/{@code HmacSHA1}); a conformidade é garantida pelos vetores
 * oficiais do RFC 6238 em {@code TotpTest}.
 *
 * <p><b>Segurança:</b> o secret tem 160 bits de entropia (20 bytes via
 * {@link SecureRandom}) — adequado para hash SHA-256 em repouso, mas nesta spec
 * é persistido **criptografado** (CryptoConverter AES-256/GCM) porque o servidor
 * precisa do valor original para recalcular o código esperado.
 */
public final class Totp {

    /** Passo padrão do RFC 6238: 30 segundos. */
    public static final int TIME_STEP_SECONDS = 30;

    /** Número de dígitos do código (padrão Google Authenticator). */
    private static final int DIGITS = 6;

    /** Janela de tolerância para clock skew (±1 passo = ±30s). */
    public static final int WINDOW_STEPS = 1;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    /** Gera um secret TOTP em base32 (20 bytes = 160 bits). */
    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return toBase32(bytes);
    }

    /**
     * Código TOTP de 6 dígitos para o secret no passo {@code timeStep} —
     * contado como {@code floor(instant.getEpochSecond() / 30)}.
     */
    public static String code(String secretBase32, Instant instant) {
        long timeStep = instant.getEpochSecond() / TIME_STEP_SECONDS;
        return code(secretBase32, timeStep);
    }

    /**
     * Código TOTP de 6 dígitos para o secret no passo {@code timeStep}
     * (RFC 6238 §5.3): HMAC-SHA1(counter) → 31-bit → mod 10^6.
     */
    public static String code(String secretBase32, long timeStep) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(fromBase32(secretBase32), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeStep).array());
            // Dynamic truncation (RFC 4226 §5.3): último nibble define o offset.
            int offset = hash[hash.length - 1] & 0x0F;
            int binary =
                    ((hash[offset] & 0x7F) << 24)
                            | ((hash[offset + 1] & 0xFF) << 16)
                            | ((hash[offset + 2] & 0xFF) << 8)
                            | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao gerar código TOTP", e);
        }
    }

    /**
     * Verifica um código dentro da janela de ±1 passo (clock skew tolerado).
     * Comparação do código em **tempo constante** (`MessageDigest.isEqual`).
     */
    public static boolean verify(String secretBase32, String code, Instant now) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return false;
        }
        for (int window = -WINDOW_STEPS; window <= WINDOW_STEPS; window++) {
            long step = now.getEpochSecond() / TIME_STEP_SECONDS + window;
            if (MessageDigest.isEqual(
                    code(secretBase32, step).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** URI {@code otpauth://totp/FoxCash:{username}?...} para o QR do app. */
    public static String otpauthUri(String username, String secretBase32) {
        return "otpauth://totp/FoxCash:" + urlEncode(username)
                + "?secret=" + secretBase32 + "&issuer=FoxCash&digits=" + DIGITS
                + "&period=" + TIME_STEP_SECONDS;
    }

    // ----------------------------------------------------------------------
    // base32 (RFC 4648) — sem padding (o padrão dos apps autenticadores)
    // ----------------------------------------------------------------------

    private static String toBase32(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    private static byte[] fromBase32(String base32) {
        String normalized = base32.toUpperCase().replaceAll("\\s+", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : normalized.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("caractere inválido em base32: " + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
