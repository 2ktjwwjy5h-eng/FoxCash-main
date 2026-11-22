package com.foxcash.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link WebhookUrlValidator} (auditoria de seguranca 2026-08-05):
 * bloqueio SSRF — URLs de webhook de saida so podem apontar para endereco
 * publico (http/https), exceto quando {@code allowLoopback} esta explicitamente
 * ligado (dev/testes). Usa IPs literais para nao depender de DNS nos testes.
 */
class WebhookUrlValidatorTest {

    @Test
    void acceptsPublicHttpAndHttps() {
        assertThat(WebhookUrlValidator.validate("http://8.8.8.8/hook", false))
                .isEqualTo("http://8.8.8.8/hook");
        assertThat(WebhookUrlValidator.validate("https://8.8.8.8/hook", false))
                .isEqualTo("https://8.8.8.8/hook");
    }

    @Test
    void normalizesByTrimming() {
        assertThat(WebhookUrlValidator.validate("  http://8.8.8.8/hook  ", false))
                .isEqualTo("http://8.8.8.8/hook");
    }

    @Test
    void blocksLoopback() {
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://127.0.0.1:8080/hook", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://localhost:8080/hook", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://[::1]/hook", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blocksPrivateAndLinkLocalRanges() {
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://10.0.0.1/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://172.16.0.1/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://192.168.1.1/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        // Metadata cloud (AWS/GCP/Azure) e IPv6 link-local.
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://169.254.169.254/latest/meta-data/", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://[fe80::1]/x", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blocksMulticastCgnatReservedAndBroadcast() {
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://224.0.0.1/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://100.64.0.1/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://192.0.0.1/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://198.18.0.1/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://0.0.0.1/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://255.255.255.255/x", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blocksV4MappedLoopback() {
        // ::ffff:127.0.0.1 — IPv4-mapped IPv6 do loopback.
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://[::ffff:127.0.0.1]/x", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blocksV4MappedSiteLocal() {
        // IPv4-mapped de faixa privada/link-local: só o unwrap (L130/L131/L135/L117)
        // expõe o endereço interno — sem unwrap, ::ffff:10.0.0.1 não é bloqueado
        // pelos checks de Inet6Address.
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://[::ffff:10.0.0.1]/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://[::ffff:192.168.1.1]/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://[::ffff:169.254.169.254]/x", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blocksV4MappedCgnat() {
        // CGNAT 100.64.0.0/10 NÃO é site-local para o JDK (só RFC1918) — o
        // bloqueio depende do unwrap manual (b0==100 && b1 in 64..127). Um mutante
        // que quebre o isV4MappedPrefix/unwrap deixaria ::ffff:100.64.0.1 passar.
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://[::ffff:100.64.0.1]/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://[::ffff:100.127.255.254]/x", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsPublicIpv6NotMapped() {
        // IPv6 global NÃO é IPv4-mapped → deve ser aceito. O unwrap incorreto
        // (L130 negate/L132/L135 booleantrue) extrairia os últimos 4 bytes
        // (ex.: 0.0.0.1 → b0==0 → forbidden) e bloquearia indevidamente.
        assertThat(WebhookUrlValidator.validate("http://[2001:db8::1]/x", false))
                .isEqualTo("http://[2001:db8::1]/x");
        // raw[10..11] == 0xFF mas raw[0] != 0 → NÃO é mapped (L130 negate).
        assertThat(WebhookUrlValidator.validate("http://[2001:db8:0:0:0:ffff:0:1]/x", false))
                .isEqualTo("http://[2001:db8:0:0:0:ffff:0:1]/x");
    }

    @Test
    void acceptsIpv6WithZeroPrefixButNotMapped() {
        // raw[0..14] == 0 e raw[15] != 0 → NÃO é mapped (raw[10..11] != 0xFF,0xFF).
        // Um mutante que retorne true no return final (L135 negate/booleantrue) ou
        // que negue o `raw.length == 16 && isV4MappedPrefix` (L117) unwrapparia os
        // últimos 4 bytes (0.0.0.2 → b0==0 → forbidden) e bloquearia indevidamente.
        assertThat(WebhookUrlValidator.validate("http://[0:0:0:0:0:0:0:2]/x", false))
                .isEqualTo("http://[0:0:0:0:0:0:0:2]/x");
        // raw[0..9] TODOS != 0 mas raw[10..11] == 0xFF,0xFF → NÃO é mapped.
        // Mutante L131 (`raw[i] == 0`) nunca retornaria false no loop e unwrapparia
        // (0.0.0.1 → forbidden), bloqueando indevidamente.
        assertThat(WebhookUrlValidator.validate("http://[1234:5678:9abc:def0:1234:ffff:0:1]/x", false))
                .isEqualTo("http://[1234:5678:9abc:def0:1234:ffff:0:1]/x");
    }

    @Test
    void blocksInvalidSchemesHostAndSize() {
        assertThatThrownBy(() -> WebhookUrlValidator.validate("ftp://8.8.8.8/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("file:///etc/passwd", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http:///x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("   ", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate(null, false))
                .isInstanceOf(IllegalArgumentException.class);
        String tooLong = "http://8.8.8.8/" + "a".repeat(600);
        assertThatThrownBy(() -> WebhookUrlValidator.validate(tooLong, false))
                .isInstanceOf(IllegalArgumentException.class);
        // Borda do limite: comprimento EXATAMENTE 500 é aceito (máximo é > 500).
        String exactlyMax = "http://8.8.8.8/" + "a".repeat(500 - "http://8.8.8.8/".length());
        assertThat(WebhookUrlValidator.validate(exactlyMax, false)).isEqualTo(exactlyMax);
    }

    @Test
    void blocksCgnatUpperBoundary() {
        // CGNAT 100.64.0.0/10: extremo inferior (100.64.0.1) e SUPERIOR (100.127.255.254).
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://100.64.0.1/x", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookUrlValidator.validate("http://100.127.255.254/x", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsLoopbackWhenExplicitlyEnabled() {
        assertThat(WebhookUrlValidator.validate("http://127.0.0.1:8080/hook", true))
                .isEqualTo("http://127.0.0.1:8080/hook");
        assertThat(WebhookUrlValidator.validate("http://localhost:8080/hook", true))
                .isEqualTo("http://localhost:8080/hook");
        // Mesmo liberado, o esquema continua obrigatorio (http/https).
        assertThatThrownBy(() -> WebhookUrlValidator.validate("file:///etc/passwd", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
