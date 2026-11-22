package com.foxcash.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Configuracao do motor de tracking (Spec 81) — {@code foxcash.tracking.*}.
 * Binding testado contra o {@code application.yml} real (padrao Spec 68/L16).
 */
@ConfigurationProperties("foxcash.tracking")
public class TrackingProperties {

    /** Kill-switch global do dispatch (funil → outbox). Default: on. */
    private boolean dispatchEnabled = true;

    /** Versao da Graph API do Facebook (CAPI) — {@code /{version}/{pixel}/events}. */
    private String capiApiVersion = "v19.0";

    /** Base URL da Graph API (configuravel p/ o mock de contrato no E2E). */
    private String capiBaseUrl = "https://graph.facebook.com";

    /** Base URL da Events API do TikTok — {@code /open_api/v1.3}. */
    private String tiktokBaseUrl = "https://business-api.tiktok.com/open_api/v1.3";

    /** Base URL do webhook da UTMify (token Bearer). */
    private String utmifyBaseUrl = "https://api.utmify.com.br";

    /** Base URL do webhook da Otimizey (token Bearer). */
    private String otimizeyBaseUrl = "https://api.otimizey.com.br";

    /** Truncamento do {@code response_payload} do {@code event_logs} (Spec 81/D5.2). */
    private int eventLogResponseMax = 500;

    public boolean isDispatchEnabled() { return dispatchEnabled; }
    public void setDispatchEnabled(boolean dispatchEnabled) { this.dispatchEnabled = dispatchEnabled; }

    public String getCapiApiVersion() { return capiApiVersion; }
    public void setCapiApiVersion(String capiApiVersion) { this.capiApiVersion = capiApiVersion; }

    public String getCapiBaseUrl() { return capiBaseUrl; }
    public void setCapiBaseUrl(String capiBaseUrl) { this.capiBaseUrl = capiBaseUrl; }

    public String getTiktokBaseUrl() { return tiktokBaseUrl; }
    public void setTiktokBaseUrl(String tiktokBaseUrl) { this.tiktokBaseUrl = tiktokBaseUrl; }

    public String getUtmifyBaseUrl() { return utmifyBaseUrl; }
    public void setUtmifyBaseUrl(String utmifyBaseUrl) { this.utmifyBaseUrl = utmifyBaseUrl; }

    public String getOtimizeyBaseUrl() { return otimizeyBaseUrl; }
    public void setOtimizeyBaseUrl(String otimizeyBaseUrl) { this.otimizeyBaseUrl = otimizeyBaseUrl; }

    public int getEventLogResponseMax() { return eventLogResponseMax; }
    public void setEventLogResponseMax(int eventLogResponseMax) { this.eventLogResponseMax = eventLogResponseMax; }

    /**
     * Fail-fast de segurança (padrão Spec 45/68): as base URLs dos provedores
     * DEVEM ser http(s) públicas — as URLs dos webhooks (UTMify/Otimizey) são o
     * destino do POST com o token do lojista (Spec 81/D6 exige SSRF-safe,
     * padrão Spec 35). Config inválida → boot falha (nunca um POST para
     * endereço interno/credenciais na URL).
     */
    @PostConstruct
    public void validate() {
        validateUrl("capi-base-url", capiBaseUrl);
        validateUrl("tiktok-base-url", tiktokBaseUrl);
        validateUrl("utmify-base-url", utmifyBaseUrl);
        validateUrl("otimizey-base-url", otimizeyBaseUrl);
    }

    private static void validateUrl(String key, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("foxcash.tracking." + key + " e obrigatorio");
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalStateException("foxcash.tracking." + key
                        + " deve ser http(s): " + url);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalStateException("foxcash.tracking." + key
                        + " sem host valido: " + url);
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalStateException("foxcash.tracking." + key
                        + " nao pode conter credenciais na URL: " + url);
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("foxcash.tracking." + key + " invalida: " + url, e);
        }
    }
}
