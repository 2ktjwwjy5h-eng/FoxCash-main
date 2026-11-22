package com.foxcash.infrastructure.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Hash de documentos/identificadores para lookup determinístico.
 *
 * <p>O ciphertext AES-GCM e nao-deterministico (IV aleatorio) e nao permite
 * busca por igualdade; o hash e uma funcao unidirecional que preserva a busca
 * exata por documento sem revelar o valor (ADR-004).
 *
 * <p><b>Entropia:</b> {@link #hash} (SHA-256 puro) e usado APENAS para valores
 * de alta entropia (chaves de API, tokens de sessao/reset — 128 bits). Para
 * identificadores de baixa entropia (CPF/CNPJ do lojista), {@link #hmac}
 * aplica HMAC-SHA256 com pepper do servidor ({@code h1:...}) — auditoria
 * 2026-08-05/B-A1: sem pepper, o espaco de CPF (~10^9) e brute-forcavel offline
 * por quem tiver leitura do banco. O buyerId (pseudonimo do bot) mantem SHA-256
 * por ser pseudonimo e depender do lookup historico no antifraude.
 */
public final class DocumentHasher {

    /** Prefixo do hash HMAC-peppered (B-A1) — permite trocar algoritmo no futuro. */
    static final String HMAC_PREFIX = "h1:";

    private DocumentHasher() {
    }

    /** SHA-256 (hex) — apenas para valores de alta entropia (chaves/tokens). */
    public static String hash(String document) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(document.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao calcular hash do documento", e);
        }
    }

    /**
     * HMAC-SHA256 (hex, prefixo {@code h1:}) do valor com a pepper do servidor
     * (B-A1) — para identificadores de baixa entropia (CPF/CNPJ). O resultado
     * depende do segredo ({@code foxcash.crypto.secret}) e nao e brute-forcavel
     * offline sem a chave.
     */
    public static String hmac(String value, String pepper) {
        if (value == null) {
            return null;
        }
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException("Pepper obrigatoria para hash de documento (B-A1)");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HMAC_PREFIX + HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao calcular HMAC do documento", e);
        }
    }
}
