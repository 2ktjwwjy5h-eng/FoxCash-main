package com.foxcash.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link ClientIpResolver} (auditoria 2026-08-05/M10): X-Forwarded-For
 * so e considerado com trust-proxy=true; sem ele, o IP vem de remoteAddr (o
 * header falsificado nao falsifica a trilha de auditoria).
 */
class ClientIpResolverTest {

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private MockHttpServletRequest cfRequest(String remoteAddr, String cfConnectingIp, String forwardedFor) {
        MockHttpServletRequest request = request(remoteAddr, forwardedFor);
        if (cfConnectingIp != null) {
            request.addHeader("CF-Connecting-IP", cfConnectingIp);
        }
        return request;
    }

    @Test
    void usesRemoteAddrWhenProxyNotTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(false);

        assertThat(resolver.resolve(request("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(request("203.0.113.9", "1.2.3.4, 10.0.0.1"))).isEqualTo("203.0.113.9");
        // CF-Connecting-IP também é ignorado sem trust-proxy (header pode ser forjado).
        assertThat(resolver.resolve(cfRequest("203.0.113.9", "198.51.100.7", "1.2.3.4")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void usesFirstForwardedForWhenProxyTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(true);

        assertThat(resolver.resolve(request("203.0.113.9", "1.2.3.4, 10.0.0.1"))).isEqualTo("1.2.3.4");
    }

    // Spec 63 (critério 7): com Cloudflare na frente, CF-Connecting-IP é o IP real
    // do cliente (o primeiro XFF com o edge do Railway no meio é o egress do CF).
    @Test
    void prefersCfConnectingIpWhenProxyTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(true);

        assertThat(resolver.resolve(cfRequest("172.64.222.182", "198.51.100.7", "172.64.222.182, 198.51.100.7")))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void fallsBackToForwardedWhenNoCfConnectingIp() {
        ClientIpResolver resolver = new ClientIpResolver(true);

        assertThat(resolver.resolve(request("203.0.113.9", "1.2.3.4, 10.0.0.1"))).isEqualTo("1.2.3.4");
    }

    @Test
    void fallsBackToRemoteAddrWhenNoForwardedHeader() {
        ClientIpResolver resolver = new ClientIpResolver(true);

        assertThat(resolver.resolve(request("203.0.113.9", null))).isEqualTo("203.0.113.9");
    }

    @Test
    void neverReturnsNull() {
        ClientIpResolver resolver = new ClientIpResolver(false);

        assertThat(resolver.resolve(null)).isEqualTo("unknown");
        assertThat(resolver.resolve(request(null, null))).isEqualTo("unknown");
    }
}
