package com.foxcash.domain.port.out;

import com.foxcash.domain.model.profile.MerchantProfile;
import com.foxcash.domain.model.profile.ProfileScope;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de perfis de operação (Spec 122 — fase 1).
 * Perfis não-deletados por padrão (soft-delete); a escrita de rota usa lock
 * pessimista (SEC-19 — serializa com cobranças concorrentes).
 */
public interface MerchantProfileRepository {

    /** Perfis não-deletados do lojista, por {@code sort_order}. */
    List<MerchantProfile> findByMerchantId(UUID merchantId);

    /** Perfil não-deletado por id (para o GET — pertinência validada no chamador). */
    Optional<MerchantProfile> findById(UUID id);

    /** Perfil não-deletado do lojista (IDOR-safe: id + merchantId). */
    Optional<MerchantProfile> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** Perfil com lock pessimista de escrita (SEC-19 — PATCH de rota/nome). */
    Optional<MerchantProfile> findByIdForUpdate(UUID id);

    MerchantProfile save(MerchantProfile profile);

    /** Id do perfil default do lojista (vazio quando não existe ainda). */
    Optional<UUID> defaultProfileIdOf(UUID merchantId);

    /**
     * Rota de gateway do perfil (leitura leve para o hot path do
     * {@code RoutingPolicyResolver} — projection, sem carregar agregados).
     */
    Optional<String> gatewayRouteOf(UUID merchantId, UUID profileId);

    /**
     * Escopo do perfil (Spec 139 — leitura leve para o hot path do
     * {@code RoutingPolicyResolver}, projection sem carregar agregados).
     * Vazio quando o perfil não existe/é de outro lojista.
     */
    Optional<ProfileScope> scopeOf(UUID merchantId, UUID profileId);
}
