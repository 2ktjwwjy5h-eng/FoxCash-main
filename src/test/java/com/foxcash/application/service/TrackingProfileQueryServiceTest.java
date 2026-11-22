package com.foxcash.application.service;

import com.foxcash.domain.model.tracking.TrackingProfile;
import com.foxcash.domain.model.tracking.TrackingStatus;
import com.foxcash.domain.port.out.EventLogRepository;
import com.foxcash.domain.port.out.TrackingProfileBotRepository;
import com.foxcash.domain.port.out.TrackingProfileRepository;
import com.foxcash.infrastructure.inbound.rest.dto.TrackingProfileResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link TrackingProfileQueryService} (Specs 81/124): lista escopada
 * por bot-set (perfil de operação — null → todos os perfis do lojista,
 * regressão); o {@code list} vazio → vazio (header inválido nunca vaza).
 */
class TrackingProfileQueryServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID BOT_1 = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    private final TrackingProfileRepository profileRepository = mock(TrackingProfileRepository.class);
    private final TrackingProfileBotRepository botLinkRepository = mock(TrackingProfileBotRepository.class);
    private final EventLogRepository eventLogRepository = mock(EventLogRepository.class);

    private TrackingProfileQueryService service;

    @BeforeEach
    void setUp() {
        service = new TrackingProfileQueryService(profileRepository, botLinkRepository, eventLogRepository);
    }

    private TrackingProfile profile(UUID id) {
        return TrackingProfile.restore(id, MERCHANT_ID, "Pixel Principal", TrackingStatus.ACTIVE,
                "123456", "token-enc", null, null, null, null, NOW, NOW);
    }

    @Test
    void listWithoutBotIdsIsWholeMerchantRegression() {
        when(profileRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(profile(PROFILE_ID)));
        when(botLinkRepository.findBotIdsByProfileId(PROFILE_ID)).thenReturn(java.util.Set.of(BOT_1));
        when(eventLogRepository.countByProfileIdAndStatus(PROFILE_ID,
                com.foxcash.domain.model.tracking.EventLogStatus.OK)).thenReturn(2L);
        when(eventLogRepository.countByProfileIdAndStatus(PROFILE_ID,
                com.foxcash.domain.model.tracking.EventLogStatus.ERROR)).thenReturn(1L);

        List<TrackingProfileResponse> result = service.list(MERCHANT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).botIds()).contains(BOT_1);
        verify(profileRepository).findByMerchantId(MERCHANT_ID);
    }

    @Test
    void listWithBotSetScopesByProfile() {
        when(profileRepository.findByMerchantIdAndBotIds(MERCHANT_ID, List.of(BOT_1)))
                .thenReturn(List.of(profile(PROFILE_ID)));
        when(botLinkRepository.findBotIdsByProfileId(PROFILE_ID)).thenReturn(java.util.Set.of(BOT_1));
        when(eventLogRepository.countByProfileIdAndStatus(PROFILE_ID,
                com.foxcash.domain.model.tracking.EventLogStatus.OK)).thenReturn(0L);
        when(eventLogRepository.countByProfileIdAndStatus(PROFILE_ID,
                com.foxcash.domain.model.tracking.EventLogStatus.ERROR)).thenReturn(0L);

        List<TrackingProfileResponse> result = service.list(MERCHANT_ID, List.of(BOT_1));

        assertThat(result).hasSize(1);
        verify(profileRepository).findByMerchantIdAndBotIds(MERCHANT_ID, List.of(BOT_1));
    }

    @Test
    void listWithEmptyBotSetReturnsNothing() {
        // Header inválido → bot-set vazio → perfis sem bot no set → vazio.
        when(profileRepository.findByMerchantIdAndBotIds(MERCHANT_ID, List.of()))
                .thenReturn(List.of());

        List<TrackingProfileResponse> result = service.list(MERCHANT_ID, List.of());

        assertThat(result).isEmpty();
        verify(profileRepository).findByMerchantIdAndBotIds(MERCHANT_ID, List.of());
    }

    @Test
    void listScopesByBotSetEvenForOtherMerchantsProfile() {
        // Perfil de outro lojista → bot-set vazio (findIds merchant-scoped) →
        // a query devolve vazio — nunca os perfis do lojista inteiro.
        when(profileRepository.findByMerchantIdAndBotIds(MERCHANT_ID, List.of()))
                .thenReturn(List.of());

        List<TrackingProfileResponse> result = service.list(MERCHANT_ID, List.of());

        assertThat(result).isEmpty();
    }
}
