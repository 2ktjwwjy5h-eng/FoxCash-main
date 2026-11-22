package com.foxcash.infrastructure.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.StoreButtonPayloadCodec;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.model.store.StoreButtonAction;
import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.model.store.StoreMessageEvent;
import com.foxcash.domain.port.in.StorefrontQueryUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.StorefrontResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST do storefront (Spec 48/50): API key do lojista
 * (ROLE_MERCHANT) devolve a configuracao agregada da loja (identidade + planos
 * ativos + botões ativos); principal nao-UUID (admin/master) e negado; sem
 * loja propaga 404.
 */
class FoxStorefrontControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final StorefrontQueryUseCase storefrontQuery = mock(StorefrontQueryUseCase.class);
    private final StoreButtonPayloadCodec buttonPayloadCodec = new StoreButtonPayloadCodec(new ObjectMapper());
    private final Authentication authentication = mock(Authentication.class);

    private FoxStorefrontController controller;

    @BeforeEach
    void setUp() {
        controller = new FoxStorefrontController(storefrontQuery, buttonPayloadCodec);
    }

    @Test
    void getReturnsStorefrontWithStore() {
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        Store store = Store.create(MERCHANT_ID, "Bella Premium", "https://logo/x.png", null,
                "Conteudo exclusivo", null, "#9B59B6", "⭐", null, null, now);
        StoreButton button = StoreButton.create(store.id(), "Instagram", StoreButtonAction.SOCIAL,
                "{\"url\":\"https://ig.com/x\"}", 0, now);
        StoreMessage message = StoreMessage.create(store.id(), StoreMessageEvent.WELCOME,
                "Olá 👋 Bem-vindo à Bella Premium.", now);
        when(storefrontQuery.get(MERCHANT_ID))
                .thenReturn(new StorefrontQueryUseCase.Storefront(store, List.of(),
                        List.of(button), List.of(message)));

        StorefrontResponse response = controller.get(authentication);

        assertThat(response.store().name()).isEqualTo("Bella Premium");
        assertThat(response.store().color()).isEqualTo("#9B59B6");
        // Seções das specs 49/50/51 — botões ativos (Spec 50) e mensagens ativas (Spec 51).
        assertThat(response.buttons()).hasSize(1);
        assertThat(response.buttons().get(0).label()).isEqualTo("Instagram");
        assertThat(response.buttons().get(0).payload()).containsEntry("url", "https://ig.com/x");
        assertThat(response.plans()).isEmpty();
        assertThat(response.messages()).containsEntry("welcome", "Olá 👋 Bem-vindo à Bella Premium.");
    }

    @Test
    void getWithNonUuidPrincipalIsForbidden() {
        when(authentication.getPrincipal()).thenReturn("admin");

        assertThatThrownBy(() -> controller.get(authentication))
                .isInstanceOf(MerchantAccessDeniedException.class);
    }

    @Test
    void getWithoutStorePropagates404() {
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
        when(storefrontQuery.get(MERCHANT_ID)).thenThrow(new StoreNotFoundException());

        assertThatThrownBy(() -> controller.get(authentication))
                .isInstanceOf(StoreNotFoundException.class);
    }
}
