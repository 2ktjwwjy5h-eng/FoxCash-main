package com.foxcash.infrastructure.security;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * SEC-21 (revisão de segurança 2026-08-11): DnsResolver PINADO da entrega de
 * webhooks de saída ({@code HttpMerchantNotifier}).
 *
 * <p><b>Vetor corrigido:</b> a proteção SSRF do outbound webhook validava a URL
 * (resolvendo o DNS e rejeitando endereços internos) e DEPOIS o cliente HTTP
 * re-resolvia o hostname na conexão — duas resoluções independentes deixavam
 * uma janela TOCTOU (DNS rebinding): um domínio do atacante podia responder IP
 * público na validação e IP interno na conexão (ex.: metadata cloud
 * 169.254.169.254), transformando o servidor em proxy SSRF.
 *
 * <p>Este resolver é a validação no MOMENTO da conexão: o Apache HttpClient 5
 * usa EXATAMENTE os endereços devolvidos aqui (não faz uma segunda resolução
 * independente) e preserva o hostname original no Host header/SNI. Com
 * {@code allowLoopback=false} (produção), QUALQUER endereço interno faz a
 * conexão falhar ({@link UnknownHostException}) — fail-closed.
 */
public final class WebhookDnsResolver implements DnsResolver {

    private final boolean allowLoopback;

    public WebhookDnsResolver(boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (!allowLoopback) {
            for (InetAddress address : addresses) {
                if (WebhookUrlValidator.isForbiddenAddress(address)) {
                    throw new UnknownHostException(
                            "host de webhook resolve para endereco interno (SSRF bloqueado): " + host);
                }
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return host;
    }
}
