package com.foxcash.domain.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link Totp} (Spec 44) — conformidade com os VETORES OFICIAIS do
 * RFC 6238 (Appendix B), 6 dígitos, SHA-1, passo de 30s. O secret de teste é
 * o base32 de {@code "12345678901234567890"} (20 bytes), usado pelo próprio RFC.
 */
class TotpTest {

    /** Secret base32 = ASCII "12345678901234567890" (vetor oficial do RFC 6238). */
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    // RFC 6238 §B.1 — TOTP-HMAC-SHA-1 (truncado para 6 dígitos: últimos 6 do valor de 8).
    // T (contador de steps) → código esperado.
    private static final Object[][] RFC6238_VECTORS = {
            // T (contador = segundos / 30, conforme RFC 6238 §B.1) → 6 dígitos.
            {1L, "287082"},
            {37037036L, "081804"},
            {37037037L, "050471"},
            {41152263L, "005924"},
            {66666666L, "279037"},
            {666666666L, "353130"},
    };

    @Test
    void matchesOfficialRfc6238Sha1Vectors() {
        for (Object[] vector : RFC6238_VECTORS) {
            long timeStep = (Long) vector[0];
            String expected = (String) vector[1];
            assertThat(Totp.code(SECRET, timeStep))
                    .as("RFC 6238 vetor T=%d", timeStep)
                    .isEqualTo(expected);
        }
    }

    @Test
    void generateSecretProducesValidBase32With160Bits() {
        String secret = Totp.generateSecret();
        // 20 bytes → 32 caracteres base32 (RFC 4648, sem padding).
        // Âncorado: o encoding NÃO pode produzir mais nem menos que 32 chars.
        assertThat(secret).matches("^[A-Z2-7]{32}$");
        // Duas gerações não repetem (SecureRandom).
        assertThat(Totp.generateSecret()).isNotEqualTo(secret);
    }

    @Test
    void base32EncodingMatchesRfc4648Vectors() throws Exception {
        // Vetores oficiais do RFC 4648 §10 (sem padding — padrão dos apps).
        assertThat(base32Encode("foobar".getBytes(StandardCharsets.UTF_8))).isEqualTo("MZXW6YTBOI");
        assertThat(base32Encode("hello".getBytes(StandardCharsets.UTF_8))).isEqualTo("NBSWY3DP");
        assertThat(base32Decode("MZXW6YTBOI"))
                .isEqualTo("foobar".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fromBase32AcceptsZeroValueCharacterAAndRejectsInvalid() {
        // 'A' é o primeiro caractere do alfabeto (valor 0) — borda do check value < 0.
        // "AA" → 1 byte (chave não vazia) → o código é calculado sem erro.
        assertThat(Totp.code("AA", 0L)).matches("\\d{6}");
        // Caractere fora do alfabeto → erro claro de validação.
        assertThatThrownBy(() -> Totp.code("9", 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base32");
    }

    @Test
    void verifyAcceptsCurrentStepCode() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, now), now)).isTrue();
    }

    @Test
    void verifyToleratesClockSkewWithinOneStep() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        // Código do passo ANTERIOR (±30s) ainda é aceito — janela ±1.
        Instant earlier = now.minusSeconds(30);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, earlier), now)).isTrue();
        // Código de 2 passos atrás NÃO é aceito (fora da janela).
        Instant twoStepsBack = now.minusSeconds(60);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, twoStepsBack), now)).isFalse();
    }

    @Test
    void verifyAcceptsFutureStepCodeWithinWindow() {
        // Janela +1: código do passo FUTURO (+30s) é aceito (clock skew à frente).
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        String future = Totp.code(SECRET, now.plusSeconds(30));

        assertThat(Totp.verify(SECRET, future, now)).isTrue();
        // Fora da janela (+60s) NÃO é aceito.
        String twoStepsAhead = Totp.code(SECRET, now.plusSeconds(60));
        assertThat(Totp.verify(SECRET, twoStepsAhead, now)).isFalse();
    }

    @Test
    void verifyRejectsWrongCodeAndMalformedInput() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        String valid = Totp.code(SECRET, now);
        String wrong = valid.equals("000000") ? "000001" : "000000";

        assertThat(Totp.verify(SECRET, wrong, now)).isFalse();
        // Códigos malformados (não-6-dígitos, nulo) → false, sem exceção.
        assertThat(Totp.verify(SECRET, "12345", now)).isFalse();
        assertThat(Totp.verify(SECRET, "abcdef", now)).isFalse();
        assertThat(Totp.verify(SECRET, null, now)).isFalse();
    }

    @Test
    void otpauthUriCarriesSecretIssuerDigitsAndPeriod() {
        String uri = Totp.otpauthUri("master", SECRET);
        assertThat(uri).startsWith("otpauth://totp/FoxCash:")
                .contains("secret=" + SECRET)
                .contains("issuer=FoxCash")
                .contains("digits=6")
                .contains("period=30");
    }

    @Test
    void otpauthUriUrlEncodesUsername() {
        // "master" em base64url (sem padding) — o username nunca vai em claro
        // direto na URI (espaços/caracteres especiais seriam inválidos).
        assertThat(Totp.otpauthUri("master", SECRET))
                .startsWith("otpauth://totp/FoxCash:bWFzdGVy");
    }

    // ---------- helpers (encoding privado verificado via reflection) ----------

    private static String base32Encode(byte[] bytes) throws Exception {
        Method method = Totp.class.getDeclaredMethod("toBase32", byte[].class);
        method.setAccessible(true);
        return (String) method.invoke(null, (Object) bytes);
    }

    private static byte[] base32Decode(String base32) throws Exception {
        Method method = Totp.class.getDeclaredMethod("fromBase32", String.class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, base32);
    }
}
