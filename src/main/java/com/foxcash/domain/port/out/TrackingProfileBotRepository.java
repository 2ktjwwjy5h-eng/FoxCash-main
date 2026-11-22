package com.foxcash.domain.port.out;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Porta de saída: vínculo perfil de tracking ↔ bot (Spec 81) — tabela
 * {@code tracking_profile_bots}.
 */
public interface TrackingProfileBotRepository {

    Set<UUID> findBotIdsByProfileId(UUID profileId);

    /** Substitui TODOS os vínculos do perfil pelos informados (CRUD do portal). */
    void replaceBotLinks(UUID profileId, Collection<UUID> botIds);

    void deleteByProfileId(UUID profileId);
}
