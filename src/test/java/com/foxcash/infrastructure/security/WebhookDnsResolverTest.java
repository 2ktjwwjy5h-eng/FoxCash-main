package com.foxcash.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link WebhookDnsResolver} (SEC-21 — revisão de segurança
 * 2026-08-11): a validação SSRF roda no MOMENTO da resolução usada na conexão
 * (fecha o TOCTOU de DNS rebinding da entrega de webhooks de saída).
 */
class WebhookDnsResolverTest {

    @Test
    void blocksLoopbackWhenLoopbackNotAllowed() {
        assertThatThrownBy(() -> new WebhookDnsResolver(false).resolve("127.0.0.1"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("endereco interno");
    }

    @Test
    void blocksPrivateRangesWhenLoopbackNotAllowed() {
        // 10/8 (site-local), 169.254.0.0/16 (link-local — metadata cloud),
        // 192.168/16, 100.64/10 (CGNAT) e IPv4-mapped IPv6 (::ffff:127.0.0.1).
        assertThatThrownBy(() -> new WebhookDnsResolver(false).resolve("10.0.0.1"))
                .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> new WebhookDnsResolver(false).resolve("169.254.169.254"))
                .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> new WebhookDnsResolver(false).resolve("192.168.1.10"))
                .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> new WebhookDnsResolver(false).resolve("100.64.0.1"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void allowsLoopbackWhenLoopbackAllowed() throws Exception {
        InetAddress[] addresses = new WebhookDnsResolver(true).resolve("127.0.0.1");
        assertThat(addresses).hasSize(1);
        assertThat(addresses[0].isLoopbackAddress()).isTrue();
    }

    @Test
    void allowsPublicAddresses() throws Exception {
        InetAddress[] addresses = new WebhookDnsResolver(false).resolve("8.8.8.8");
        assertThat(addresses).hasSize(1);
        assertThat(WebhookUrlValidator.isForbiddenAddress(addresses[0])).isFalse();
    }

    @Test
    void blocksIpv4MappedIpv6Loopback() {
        // ::ffff:127.0.0.1 (IPv4-mapped) — sem o unwrap, o isLoopbackAddress()
        // seria false e o endereço interno passaria.
        assertThatThrownBy(() -> new WebhookDnsResolver(false).resolve("::ffff:127.0.0.1"))
                .isInstanceOf(UnknownHostException.class);
    }
}
