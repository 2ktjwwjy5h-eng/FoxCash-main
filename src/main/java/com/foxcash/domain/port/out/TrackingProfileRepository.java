package com.foxcash.domain.port.out;

import com.foxcash.domain.model.tracking.TrackingProfile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de perfis de tracking (Spec 81). O acesso do
 * portal é escopado por {@code merchantId} (404 cross-lojista no service); o
 * dispatch localiza perfis ACTIVE pelo bot (vínculo {@code tracking_profile_bots}).
 */
public interface TrackingProfileRepository {

    TrackingProfile save(TrackingProfile profile);

    /** Lookup sem escopo — usado pelo handler do outbox (perfil já validado na origem). */
    Optional<TrackingProfile> findById(UUID id);

    Optional<TrackingProfile> findByIdAndMerchantId(UUID id, UUID merchantId);

    List<TrackingProfile> findByMerchantId(UUID merchantId);

    /**
     * Perfis do lojista escopados por bot-set (Spec 124 — filtro rígido por
     * perfil): perfis cujos bots vinculados ({@code tracking_profile_bots})
     * estão no bot-set do perfil ativo. Lista vazia → vazio (header inválido
     * nunca vaza).
     */
    List<TrackingProfile> findByMerchantIdAndBotIds(UUID merchantId, List<UUID> botIds);

    /** Perfis ACTIVE vinculados a um bot (Spec 81/D5.1 — dispatch). */
    List<TrackingProfile> findActiveByBotId(UUID botId);

    void delete(TrackingProfile profile);
}
