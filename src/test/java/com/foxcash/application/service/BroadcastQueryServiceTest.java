package com.foxcash.application.service;

import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.port.out.BroadcastLogRepository;
import com.foxcash.domain.port.out.BroadcastRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link BroadcastQueryService} (Specs 79/124): listagem com filtro
 * por bot-set (perfil de operação — null → lojista inteiro, regressão),
 * detalhe/logs 404 cross-lojista.
 */
class BroadcastQueryServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID BOT_1 = UUID.randomUUID();
    private static final UUID BOT_2 = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    private final BroadcastRepository broadcastRepository = mock(BroadcastRepository.class);
    private final BroadcastLogRepository logRepository = mock(BroadcastLogRepository.class);

    private BroadcastQueryService service;

    @BeforeEach
    void setUp() {
        service = new BroadcastQueryService(broadcastRepository, logRepository);
    }

    private Broadcast broadcast(UUID storeBotId) {
        return Broadcast.restore(UUID.randomUUID(), storeBotId, BroadcastMode.NOW, "-100123456789",
                "Promoção!", null, null, null, null, BroadcastStatus.PENDING, null,
                null, null, NOW, NOW);
    }

    @Test
    void listWithoutBotIdsIsWholeMerchantRegression() {
        Broadcast b = broadcast(BOT_1);
        when(broadcastRepository.findByMerchantId(MERCHANT_ID, null, null)).thenReturn(List.of(b));

        var result = service.list(MERCHANT_ID, null, null, null);

        assertThat(result).hasSize(1);
        verify(broadcastRepository).findByMerchantId(MERCHANT_ID, null, null);
    }

    @Test
    void listWithBotSetScopesByProfile() {
        Broadcast b = broadcast(BOT_1);
        when(broadcastRepository.findByMerchantIdAndBotIds(MERCHANT_ID, List.of(BOT_1), null, null))
                .thenReturn(List.of(b));

        var result = service.list(MERCHANT_ID, List.of(BOT_1), null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).storeBotId()).isEqualTo(BOT_1);
        verify(broadcastRepository).findByMerchantIdAndBotIds(MERCHANT_ID, List.of(BOT_1), null, null);
    }

    @Test
    void listWithEmptyBotSetReturnsNothing() {
        // Header inválido → bot-set vazio → IN () → vazio (nunca lojista inteiro).
        when(broadcastRepository.findByMerchantIdAndBotIds(MERCHANT_ID, List.of(), null, null))
                .thenReturn(List.of());

        var result = service.list(MERCHANT_ID, List.of(), null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void getThrows404ForOtherMerchant() {
        Broadcast foreign = broadcast(BOT_2);
        when(broadcastRepository.findByIdAndMerchantId(foreign.id(), MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MERCHANT_ID, foreign.id()))
                .isInstanceOf(BroadcastNotFoundException.class);
    }
}
