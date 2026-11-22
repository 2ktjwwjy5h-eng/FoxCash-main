package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.StoreBotQueryService;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.infrastructure.inbound.rest.dto.MasterBotResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do espelho read-only do Master Console (Spec 74/T4.4): listagem de
 * todos os lojistas (com nome do lojista) e detalhe; sem ações de escrita.
 */
class MasterBotControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    private final StoreBotQueryService queryService = mock(StoreBotQueryService.class);
    private MasterBotController controller;

    @BeforeEach
    void setUp() {
        controller = new MasterBotController(queryService);
    }

    private StoreBot bot() {
        return StoreBot.restore(UUID.randomUUID(), MERCHANT_ID, BotPlatform.TELEGRAM,
                "123456:ABC-secret-token", "hash", "wh-hash", "Bot Vendas", "vendasbot", null,
                BotStatus.ACTIVE, null, NOW, null, NOW, NOW);
    }

    @Test
    void listReturnsAllBotsWithMerchantName() {
        StoreBot b = bot();
        when(queryService.listAllForMaster())
                .thenReturn(List.of(new StoreBotQueryService.MasterBotView(b, "Loja X")));

        List<MasterBotResponse> response = controller.list();

        assertThat(response).hasSize(1);
        assertThat(response.get(0).merchantName()).isEqualTo("Loja X");
        assertThat(response.get(0).tokenMask()).doesNotContain("123456"); // sem token
    }

    @Test
    void getReturnsBotWithMerchantName() {
        StoreBot b = bot();
        when(queryService.getForMaster(b.id()))
                .thenReturn(new StoreBotQueryService.MasterBotView(b, "Loja Y"));

        MasterBotResponse response = controller.get(b.id());

        assertThat(response.id()).isEqualTo(b.id());
        assertThat(response.merchantName()).isEqualTo("Loja Y");
    }

    @Test
    void getUnknownBotPropagates404() {
        UUID botId = UUID.randomUUID();
        when(queryService.getForMaster(botId)).thenThrow(new StoreBotNotFoundException());

        assertThatThrownBy(() -> controller.get(botId))
                .isInstanceOf(StoreBotNotFoundException.class);
    }
}
