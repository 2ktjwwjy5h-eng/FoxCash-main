package com.foxcash.domain.model.tracking;

import java.time.Instant;
import java.util.UUID;

/**
 * Perfil de tracking de um lojista (Spec 81/D3) — configuração dos provedores
 * (Facebook CAPI, TikTok, UTMify, Otimizey). Aggregate: validação por provedor
 * (pixel + token juntos), transições activate/deactivate/duplicate e máscara de
 * token (nunca exposto em resposta HTTP). Os tokens ficam em claro apenas em
 * memória; a persistência criptografa em repouso (CryptoConverter — Spec 74).
 */
public class TrackingProfile {

    public static final int MAX_NAME = 100;

    private final UUID id;
    private final UUID merchantId;
    private String name;
    private TrackingStatus status;
    private String facebookPixelId;
    private String facebookAccessToken;   // em claro apenas em memória
    private String tiktokPixelId;
    private String tiktokAccessToken;
    private String utmifyToken;
    private String otimizeyToken;
    private final Instant createdAt;
    private Instant updatedAt;

    private TrackingProfile(UUID id, UUID merchantId, String name, TrackingStatus status,
                            String facebookPixelId, String facebookAccessToken,
                            String tiktokPixelId, String tiktokAccessToken,
                            String utmifyToken, String otimizeyToken,
                            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.name = name;
        this.status = status;
        this.facebookPixelId = facebookPixelId;
        this.facebookAccessToken = facebookAccessToken;
        this.tiktokPixelId = tiktokPixelId;
        this.tiktokAccessToken = tiktokAccessToken;
        this.utmifyToken = utmifyToken;
        this.otimizeyToken = otimizeyToken;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static TrackingProfile create(UUID merchantId, String name, String facebookPixelId,
                                         String facebookAccessToken, String tiktokPixelId,
                                         String tiktokAccessToken, String utmifyToken,
                                         String otimizeyToken, Instant now) {
        validate(name, facebookPixelId, facebookAccessToken, tiktokPixelId,
                tiktokAccessToken, utmifyToken, otimizeyToken);
        return new TrackingProfile(UUID.randomUUID(), merchantId, name, TrackingStatus.ACTIVE,
                normalize(facebookPixelId), facebookAccessToken, normalize(tiktokPixelId),
                tiktokAccessToken, utmifyToken, otimizeyToken, now, now);
    }

    public static TrackingProfile restore(UUID id, UUID merchantId, String name, TrackingStatus status,
                                          String facebookPixelId, String facebookAccessToken,
                                          String tiktokPixelId, String tiktokAccessToken,
                                          String utmifyToken, String otimizeyToken,
                                          Instant createdAt, Instant updatedAt) {
        return new TrackingProfile(id, merchantId, name, status, facebookPixelId,
                facebookAccessToken, tiktokPixelId, tiktokAccessToken, utmifyToken,
                otimizeyToken, createdAt, updatedAt);
    }

    /** Atualiza nome e configurações; mantém status e timestamps de auditoria. */
    public void update(String newName, String facebookPixelId, String facebookAccessToken,
                       String tiktokPixelId, String tiktokAccessToken, String utmifyToken,
                       String otimizeyToken, Instant now) {
        validate(newName, facebookPixelId, facebookAccessToken, tiktokPixelId,
                tiktokAccessToken, utmifyToken, otimizeyToken);
        this.name = newName.trim();
        this.facebookPixelId = normalize(facebookPixelId);
        this.facebookAccessToken = facebookAccessToken;
        this.tiktokPixelId = normalize(tiktokPixelId);
        this.tiktokAccessToken = tiktokAccessToken;
        this.utmifyToken = utmifyToken;
        this.otimizeyToken = otimizeyToken;
        this.updatedAt = now;
    }

    public void activate(Instant now) {
        this.status = TrackingStatus.ACTIVE;
        this.updatedAt = now;
    }

    public void deactivate(Instant now) {
        this.status = TrackingStatus.INACTIVE;
        this.updatedAt = now;
    }

    /** Cópia para um novo perfil (novo id, mesmo merchant, status ACTIVE, ids novos). */
    public TrackingProfile duplicate(UUID merchantId, String newName, Instant now) {
        validate(newName, facebookPixelId, facebookAccessToken, tiktokPixelId,
                tiktokAccessToken, utmifyToken, otimizeyToken);
        return new TrackingProfile(UUID.randomUUID(), merchantId, newName.trim(), TrackingStatus.ACTIVE,
                facebookPixelId, facebookAccessToken, tiktokPixelId, tiktokAccessToken,
                utmifyToken, otimizeyToken, now, now);
    }

    /** Máscara de token para resposta HTTP — últimos 4 chars (padrão Spec 74). */
    public static String tokenMask(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int len = token.length();
        return len <= 4 ? "****" : "****" + token.substring(len - 4);
    }

    public boolean isFacebookConfigured() {
        return facebookPixelId != null && facebookAccessToken != null
                && !facebookAccessToken.isBlank();
    }

    public boolean isTikTokConfigured() {
        return tiktokPixelId != null && tiktokAccessToken != null
                && !tiktokAccessToken.isBlank();
    }

    public boolean isUtmifyConfigured() {
        return utmifyToken != null && !utmifyToken.isBlank();
    }

    public boolean isOtimizeyConfigured() {
        return otimizeyToken != null && !otimizeyToken.isBlank();
    }

    // ---- Leitura ----

    public UUID id() { return id; }
    public UUID merchantId() { return merchantId; }
    public String name() { return name; }
    public TrackingStatus status() { return status; }
    public String facebookPixelId() { return facebookPixelId; }
    public String facebookAccessToken() { return facebookAccessToken; }
    public String tiktokPixelId() { return tiktokPixelId; }
    public String tiktokAccessToken() { return tiktokAccessToken; }
    public String utmifyToken() { return utmifyToken; }
    public String otimizeyToken() { return otimizeyToken; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    private static void validate(String name, String facebookPixelId, String facebookAccessToken,
                                 String tiktokPixelId, String tiktokAccessToken,
                                 String utmifyToken, String otimizeyToken) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name e obrigatorio");
        }
        if (name.trim().length() > MAX_NAME) {
            throw new IllegalArgumentException("name deve ter no maximo " + MAX_NAME + " caracteres");
        }
        pair("facebook", facebookPixelId, facebookAccessToken);
        pair("tiktok", tiktokPixelId, tiktokAccessToken);
    }

    /** Provedor de pixel exige token junto (e vice-versa) — evita config quebrada. */
    private static void pair(String service, String pixelId, String token) {
        boolean hasPixel = pixelId != null && !pixelId.isBlank();
        boolean hasToken = token != null && !token.isBlank();
        if (hasPixel != hasToken) {
            throw new IllegalArgumentException(
                    service + ": pixel_id e token devem ser informados juntos");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
