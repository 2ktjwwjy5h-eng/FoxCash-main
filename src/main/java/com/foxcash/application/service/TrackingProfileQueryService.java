package com.foxcash.application.service;

import com.foxcash.domain.exception.TrackingProfileNotFoundException;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.tracking.EventLog;
import com.foxcash.domain.model.tracking.EventLogStatus;
import com.foxcash.domain.model.tracking.TrackingProfile;
import com.foxcash.domain.port.out.EventLogRepository;
import com.foxcash.domain.port.out.TrackingProfileBotRepository;
import com.foxcash.domain.port.out.TrackingProfileRepository;
import com.foxcash.infrastructure.inbound.rest.dto.TrackingEventLogResponse;
import com.foxcash.infrastructure.inbound.rest.dto.TrackingProfileResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Aplicação: leitura de perfis de tracking do portal (Spec 81/D7) — lista com
 * badges e resumo do log (Spec 82), detalhe SEM tokens, log de eventos paginado.
 */
@Service
public class TrackingProfileQueryService {

    private final TrackingProfileRepository profileRepository;
    private final TrackingProfileBotRepository botLinkRepository;
    private final EventLogRepository eventLogRepository;

    public TrackingProfileQueryService(TrackingProfileRepository profileRepository,
                                       TrackingProfileBotRepository botLinkRepository,
                                       EventLogRepository eventLogRepository) {
        this.profileRepository = profileRepository;
        this.botLinkRepository = botLinkRepository;
        this.eventLogRepository = eventLogRepository;
    }

    @Transactional(readOnly = true)
    public List<TrackingProfileResponse> list(UUID merchantId) {
        return list(merchantId, null);
    }

    /**
     * Lista de perfis de tracking escopada por bot-set (Spec 124 — filtro
     * rígido por perfil): {@code botIds} null → todos os perfis do lojista
     * (regressão — sem header); presente → só os perfis com bot vinculado no
     * bot-set (vazio → sem resultados — header inválido nunca vaza).
     */
    @Transactional(readOnly = true)
    public List<TrackingProfileResponse> list(UUID merchantId, List<UUID> botIds) {
        List<TrackingProfile> profiles = botIds == null
                ? profileRepository.findByMerchantId(merchantId)
                : profileRepository.findByMerchantIdAndBotIds(merchantId, botIds);
        return profiles.stream().map(profile -> toResponse(profile)).toList();
    }

    @Transactional(readOnly = true)
    public TrackingProfileResponse detail(UUID merchantId, UUID id) {
        return toResponse(requireProfile(merchantId, id));
    }

    @Transactional(readOnly = true)
    public PageResult<TrackingEventLogResponse> events(UUID merchantId, UUID id, int page, int size) {
        TrackingProfile profile = requireProfile(merchantId, id);
        PageResult<EventLog> result = eventLogRepository.findByProfileId(profile.id(), page, size);
        return PageResult.of(
                result.items().stream().map(TrackingEventLogResponse::from).toList(),
                result.page(), result.size(), result.totalElements());
    }

    private TrackingProfileResponse toResponse(TrackingProfile profile) {
        Set<UUID> botIds = botLinkRepository.findBotIdsByProfileId(profile.id());
        long ok = eventLogRepository.countByProfileIdAndStatus(profile.id(), EventLogStatus.OK);
        long error = eventLogRepository.countByProfileIdAndStatus(profile.id(), EventLogStatus.ERROR);
        return TrackingProfileResponse.from(profile, botIds, ok, error);
    }

    private TrackingProfile requireProfile(UUID merchantId, UUID id) {
        return profileRepository.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(TrackingProfileNotFoundException::new);
    }
}
