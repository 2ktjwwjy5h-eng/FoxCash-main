package com.foxcash.domain.model.profile;

import com.foxcash.domain.exception.InvalidProfileNameException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Perfil de operação de um lojista (Spec 122 — fase 1, fundação do seletor de
 * contexto do portal). Cada lojista tem N perfis; cada perfil carrega a
 * {@code gatewayRoute} (rota de cobrança Pix — {@code null}/PADRAO = roteamento
 * por risco, Spec 91), um {@link ProfileScope} (Spec 139 — GENERAL/BOT_ONLY/
 * GATEWAY_ONLY) e aponta para uma frota de bots ({@code store_bots.profile_id}).
 *
 * <p>O perfil é o elo entre o **contexto** do portal (rota de gateway + frota de
 * bots + escopo de dados na fase 2, Spec 124) e o lojista. A fonte da verdade
 * da rota é o banco — o header {@code X-Profile-ID} apenas seleciona o perfil
 * (nunca decide rota).</p>
 */
public class MerchantProfile {

    private static final int MAX_NAME = 64;

    private final UUID id;
    private final UUID merchantId;
    private String name;
    private String gatewayRoute;   // null = PADRAO
    private ProfileScope scope;    // Spec 139 — default GENERAL
    private final boolean isDefault;
    private final int sortOrder;
    private Instant deletedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    private MerchantProfile(UUID id, UUID merchantId, String name, String gatewayRoute,
                            ProfileScope scope, boolean isDefault, int sortOrder, Instant deletedAt,
                            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.name = name;
        this.gatewayRoute = gatewayRoute;
        this.scope = scope == null ? ProfileScope.GENERAL : scope;
        this.isDefault = isDefault;
        this.sortOrder = sortOrder;
        this.deletedAt = deletedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Cria um novo perfil (rota vazia = PADRAO; escopo default GENERAL). */
    public static MerchantProfile create(UUID merchantId, String name, boolean isDefault,
                                         int sortOrder, Instant now) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        validateName(name);
        Objects.requireNonNull(now, "now must not be null");
        return new MerchantProfile(UUID.randomUUID(), merchantId, name.trim(), null,
                ProfileScope.GENERAL, isDefault, sortOrder, null, now, now);
    }

    /** Restaura do banco (sem validações — o dado persistido é confiável). */
    public static MerchantProfile restore(UUID id, UUID merchantId, String name, String gatewayRoute,
                                          ProfileScope scope, boolean isDefault, int sortOrder,
                                          Instant deletedAt, Instant createdAt, Instant updatedAt) {
        return new MerchantProfile(id, merchantId, name, gatewayRoute, scope, isDefault, sortOrder,
                deletedAt, createdAt, updatedAt);
    }

    // ---- Mutações ----

    /** Renomeia o perfil (trim, 1..64). */
    public void rename(String name, Instant now) {
        validateName(name);
        this.name = name.trim();
        this.updatedAt = now;
    }

    /**
     * Muda a rota de gateway do perfil. {@code null}/{@code PADRAO}/blank →
     * {@code null} (roteamento por risco); demais → lowercase.
     */
    public void changeGatewayRoute(String route, Instant now) {
        this.gatewayRoute = (route == null || route.isBlank() || "PADRAO".equalsIgnoreCase(route))
                ? null : route.toLowerCase();
        this.updatedAt = now;
    }

    /**
     * Muda o escopo do perfil (Spec 139). A consistência com rota/frota
     * (BOT_ONLY sem rota, GATEWAY_ONLY sem frota) é defesa da aplicação.
     */
    public void changeScope(ProfileScope scope, Instant now) {
        this.scope = scope == null ? ProfileScope.GENERAL : scope;
        this.updatedAt = now;
    }

    // ---- Leitura ----

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String name() {
        return name;
    }

    /** Rota fixada ({@code null} = PADRAO). Nunca {@code "PADRAO"} persistido. */
    public String gatewayRoute() {
        return gatewayRoute;
    }

    /** Escopo do perfil (Spec 139 — default {@code GENERAL}). */
    public ProfileScope scope() {
        return scope;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidProfileNameException("name e obrigatorio no perfil");
        }
        if (name.trim().length() > MAX_NAME) {
            throw new InvalidProfileNameException("name deve ter no maximo " + MAX_NAME + " caracteres");
        }
    }
}
