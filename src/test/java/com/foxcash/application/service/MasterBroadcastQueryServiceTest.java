package com.foxcash.application.service;

import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.BroadcastLogRepository;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterBroadcastQueryService} (Spec 89) — listagem
 * cross-merchant com dados de lojista/bot e detalhe (404).
 */
class MasterBroadcastQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    private final BroadcastRepository broadcastRepository = mock(BroadcastRepository.class);
    private final BroadcastLogRepository logRepository = mock(BroadcastLogRepository.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private MasterBroadcastQueryService service;

    @BeforeEach
    void setUp() {
        service = new MasterBroadcastQueryService(broadcastRepository, logRepository,
                botRepository, merchantRepository);
    }

    private Broadcast broadcast(UUID id, UUID botId) {
        return Broadcast.restore(id, botId, BroadcastMode.NOW, "chat-1", "conteudo", null, null,
                null, null, BroadcastStatus.PENDING, null, null, null, NOW, NOW);
    }

    private StoreBot bot(UUID id, UUID merchantId) {
        return StoreBot.restore(id, merchantId, BotPlatform.TELEGRAM,
                "123456:ABC", "hash", "wh-hash", "Bot Vendas", "vendasbot", null,
                BotStatus.ACTIVE, null, NOW, null, NOW, NOW);
    }

    @Test
    void listReturnsSummariesWithMerchantAndBotData() {
        UUID botId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Broadcast b = broadcast(UUID.randomUUID(), botId);
        when(broadcastRepository.searchMaster(any())).thenReturn(
                PageResult.of(List.of(b), 0, 20, 1));
        when(botRepository.findById(botId)).thenReturn(Optional.of(bot(botId, merchantId)));
        when(merchantRepository.findById(merchantId))
                .thenReturn(Optional.of(Merchant.restore(merchantId, "Loja X", null, null, null,
                        MerchantStatus.ACTIVE, null, Money.of("0.00"), false, NOW)));

        PageResult<MasterBroadcastQueryService.MasterBroadcastView> result =
                service.list(new BroadcastRepository.MasterBroadcastSearchQuery(
                        null, null, null, null, null, null, 0, 20));

        assertThat(result.items()).hasSize(1);
        MasterBroadcastQueryService.MasterBroadcastView view = result.items().get(0);
        assertThat(view.merchantName()).isEqualTo("Loja X");
        assertThat(view.bot().displayName()).isEqualTo("Bot Vendas");
        assertThat(view.broadcast().content()).isEqualTo("conteudo");
    }

    @Test
    void detailUnknownBroadcastThrows404() {
        UUID id = UUID.randomUUID();
        when(broadcastRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(id))
                .isInstanceOf(BroadcastNotFoundException.class);
    }

    @Test
    void detailReturnsViewWithMerchantName() {
        UUID botId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Broadcast b = broadcast(UUID.randomUUID(), botId);
        when(broadcastRepository.findById(b.id())).thenReturn(Optional.of(b));
        when(botRepository.findById(botId)).thenReturn(Optional.of(bot(botId, merchantId)));
        when(merchantRepository.findById(merchantId))
                .thenReturn(Optional.of(Merchant.restore(merchantId, "Loja Z", null, null, null,
                        MerchantStatus.ACTIVE, null, Money.of("0.00"), false, NOW)));

        MasterBroadcastQueryService.MasterBroadcastView view = service.detail(b.id());

        assertThat(view.broadcast().id()).isEqualTo(b.id());
        assertThat(view.merchantName()).isEqualTo("Loja Z");
    }

    @Test
    void listForwardsAllFiltersToRepository() {
        UUID merchantId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        Instant from = NOW.minusSeconds(3600);
        Instant to = NOW;
        when(broadcastRepository.searchMaster(any())).thenReturn(PageResult.of(List.of(), 0, 20, 0));

        service.list(new BroadcastRepository.MasterBroadcastSearchQuery(
                merchantId, botId, BroadcastStatus.PENDING, BroadcastMode.SCHEDULED,
                from, to, 2, 50));

        // O service repassa o filtro completo (sem transformação) ao repositório
        // — o adapter monta a Specification com esses valores (D2.1/D7).
        org.mockito.ArgumentCaptor<BroadcastRepository.MasterBroadcastSearchQuery> captor =
                org.mockito.ArgumentCaptor.forClass(BroadcastRepository.MasterBroadcastSearchQuery.class);
        verify(broadcastRepository).searchMaster(captor.capture());
        BroadcastRepository.MasterBroadcastSearchQuery q = captor.getValue();
        assertThat(q.merchantId()).isEqualTo(merchantId);
        assertThat(q.botId()).isEqualTo(botId);
        assertThat(q.status()).isEqualTo(BroadcastStatus.PENDING);
        assertThat(q.mode()).isEqualTo(BroadcastMode.SCHEDULED);
        assertThat(q.from()).isEqualTo(from);
        assertThat(q.to()).isEqualTo(to);
        assertThat(q.page()).isEqualTo(2);
        assertThat(q.size()).isEqualTo(50);
    }
}
