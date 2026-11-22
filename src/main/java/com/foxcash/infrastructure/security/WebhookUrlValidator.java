package com.foxcash.infrastructure.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Valida a URL de webhook de saida (Spec 35) contra SSRF (auditoria de
 * seguranca 2026-08-05).
 *
 * <p>Contrato: apenas {@code http}/{@code https}, host obrigatorio, e — a menos
 * que {@code allowLoopback} esteja explicitamente ligado (dev/testes com
 * receptor local) — o host NAO pode resolver para endereco interno: loopback
 * (127.0.0.0/8, ::1), link-local (169.254.0.0/16, fe80::/10), site-local
 * (10/8, 172.16/12, 192.168/16, fec0::/10), multicast, CGNAT (100.64.0.0/10),
 * 0.0.0.0/8, 192.0.0.0/24, 198.18.0.0/15 e broadcast. Isso impede o uso do
 * servidor como proxy para metadata cloud ({@code 169.254.169.254}) ou rede
 * interna. A validacao resolve o DNS no momento da chamada (re-checagem na
 * entrega protege contra URLs cadastradas antes do controle e contra troca de
 * resolucao).
 */
public final class WebhookUrlValidator {

    /** Tamanho maximo da URL (coluna {@code merchant_webhooks.url} VARCHAR(500)). */
    private static final int MAX_URL_LENGTH = 500;

    private WebhookUrlValidator() {
    }

    /**
     * Valida a URL e devolve a versao normalizada (trim).
     *
     * @throws IllegalArgumentException quando a URL viola o contrato (400
     *                                  {@code invalid_argument} no REST).
     */
    public static String validate(String url, boolean allowLoopback) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL de webhook obrigatoria");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException(
                    "URL de webhook muito longa (maximo " + MAX_URL_LENGTH + " caracteres)");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL de webhook invalida");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("URL de webhook deve usar os esquemas http ou https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL de webhook sem host");
        }
        if (!allowLoopback) {
            requirePublicHost(host);
        }
        return trimmed;
    }

    /** Resolve o host e rejeita se QUALQUER endereco resolvido for interno. */
    private static void requirePublicHost(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isForbiddenAddress(address)) {
                    throw new IllegalArgumentException(
                            "URL de webhook deve apontar para um endereco publico (SSRF bloqueado)");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("URL de webhook com host inexistente: " + host);
        }
    }

    /**
     * SEC-21 (revisão de segurança 2026-08-11): rejeita endereço interno/privado.
     * Package-private — reutilizado pelo {@link WebhookDnsResolver} para validar
     * os MESMOS endereços usados na conexão (fecha o TOCTOU de DNS rebinding:
     * antes, a validação resolvia o DNS e o cliente re-resolvia na conexão).
     */
    static boolean isForbiddenAddress(InetAddress address) {
        InetAddress candidate = unwrapV4Mapped(address);
        if (candidate.isAnyLocalAddress()
                || candidate.isLoopbackAddress()
                || candidate.isLinkLocalAddress()
                || candidate.isSiteLocalAddress()
                || candidate.isMulticastAddress()) {
            return true;
        }
        if (candidate instanceof Inet4Address v4) {
            int b0 = v4.getAddress()[0] & 0xFF;
            int b1 = v4.getAddress()[1] & 0xFF;
            // Faixas reservadas nao cobertas pelos metodos acima:
            // 0.0.0.0/8, 100.64.0.0/10 (CGNAT), 192.0.0.0/24, 198.18.0.0/15,
            // 255.255.255.255 (broadcast).
            if (b0 == 0 || b0 == 255) {
                return true;
            }
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true;
            }
            if (b0 == 192 && b1 == 0) {
                return true;
            }
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;
            }
        }
        return false;
    }

    /** Extrai o IPv4 embutido de enderecos IPv4-mapped IPv6 ({@code ::ffff:a.b.c.d}). */
    private static InetAddress unwrapV4Mapped(InetAddress address) {
        if (address instanceof Inet6Address v6) {
            byte[] raw = v6.getAddress();
            if (raw.length == 16 && isV4MappedPrefix(raw)) {
                byte[] v4 = {raw[12], raw[13], raw[14], raw[15]};
                try {
                    return InetAddress.getByAddress(v4);
                } catch (UnknownHostException e) {
                    return address; // inatingivel; mantem o original
                }
            }
        }
        return address;
    }

    private static boolean isV4MappedPrefix(byte[] raw) {
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        return raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF;
    }
}
