package com.foxcash.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuracao da inspecao de corpo (Spec 63 — camada app) —
 * {@code foxcash.security.body-inspection.*}.
 *
 * <p>Filtro que detecta payloads de ataque (SQLi/XSS/path traversal) no RAW
 * body de POST/PUT/PATCH <b>antes</b> da autenticacao e do rate limit —
 * cobre o corpo que o WAF do Cloudflare (plano Free) nao inspeciona. Padroes
 * calibrados por COMBINACAO (nunca palavras soltas) para minimizar falso
 * positivo; desligavel por env {@code FOXCASH_BODY_INSPECTION_ENABLED=false}.
 *
 * <p>Webhooks do PSP ({@code /webhooks/pix}, {@code /webhooks/payouts}) sao
 * excluidos por default — a reentrega legitima nunca e sacrificada
 * (disponibilidade primeiro, ADR-010; mesmo criterio 6 da Spec 63).
 */
@ConfigurationProperties("foxcash.security.body-inspection")
public class BodyInspectionProperties {

    /** Liga/desliga o filtro (dev/CI podem desligar; prod default ON). */
    private boolean enabled = true;

    /**
     * Teto de bytes inspecionados (default 1 MiB — espelha o teto global do
     * {@link RequestBodySizeFilter}). Acima disso a inspecao cobre so o
     * prefixo; o corpo completo segue para o controller.
     */
    private int maxInspectBytes = 1_048_576;

    /** Prefixos de path excluidos da inspecao (webhooks do PSP por default). */
    private List<String> excludedPaths = new ArrayList<>(List.of(
            "/webhooks/pix", "/webhooks/payouts"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxInspectBytes() {
        return maxInspectBytes;
    }

    public void setMaxInspectBytes(int maxInspectBytes) {
        this.maxInspectBytes = maxInspectBytes;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }
}
