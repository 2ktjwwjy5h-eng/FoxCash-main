package com.foxcash.infrastructure.notification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Assinatura v2 do webhook de saida (Spec 148) — {@code X-FoxCash-Signature}
 * no formato {@code t=<unix>,v1=<hex>}, onde {@code hex = HMAC-SHA256(secret,
 * "<t>.<rawBody>")}.
 *
 * <p>O {@code t} (epoch seconds) permite ao receptor validar a FRESCURA da
 * mensagem (janela anti-replay recomendada: 5 min — rejeitar {@code t} antigo
 * ou no futuro). Mantém o mesmo header {@code X-FoxCash-Signature} — versiona o
 * FORMATO (v1 hex puro → v2 {@code t=,v1=}) em vez de criar header novo.
 */
public final class WebhookSignatureV2 {

    private static final String HMAC_ALGO = "HmacSHA256";

    private WebhookSignatureV2() {
    }

    /** {@code t=<epochSeconds>,v1=<hex>} com o {@code t} atual (UTC). */
    public static String serialize(String secret, String rawBody) {
        return serialize(secret, rawBody, Instant.now().getEpochSecond());
    }

    /** {@code t=<epochSeconds>,v1=<hex>} com {@code t} explícito (teste). */
    public static String serialize(String secret, String rawBody, long t) {
        String hex = hmac(secret, t + "." + rawBody);
        return "t=" + t + ",v1=" + hex;
    }

    /** HMAC-SHA256 hex (lowercase) do dado com o segredo (padrão Spec 10/11). */
    public static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao assinar webhook v2", e);
        }
    }
}
