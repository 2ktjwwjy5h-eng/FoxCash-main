package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.model.store.StoreButtonAction;
import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.model.store.StoreMessageEvent;
import com.foxcash.domain.port.in.StorefrontQueryUseCase.Storefront;
import com.foxcash.domain.port.out.PlanRepository;
import com.foxcash.domain.port.out.StoreButtonRepository;
import com.foxcash.domain.port.out.StoreMessageRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do storefront (Spec 48/49/50/51): devolve a loja do principal (fonte
 * de verdade do bot) + planos ATIVOS (Spec 49) + botões ATIVOS em ordem
 * (Spec 50) + mensagens por etapa ATIVAS (Spec 51) e 404
 * {@code store_not_found} sem loja.
 */
class StorefrontServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final StoreButtonRepository buttonRepository = mock(StoreButtonRepository.class);
    private final StoreMessageRepository messageRepository = mock(StoreMessageRepository.class);

    private StorefrontService service;

    @BeforeEach
    void setUp() {
        service = new StorefrontService(storeRepository, planRepository, buttonRepository,
                messageRepository);
    }

    @Test
    void getReturnsStoreIdentityWithActivePlansButtonsAndMessages() {
        Instant now = Instant.parse("2026-08-06T12:00:00Z");
        Store store = Store.create(MERCHANT_ID, "Bella Premium", "https://logo/x.png",
                null, "Conteudo exclusivo", null, "#9B59B6", "⭐", null, null, now);
        Plan plan = Plan.create(store.id(), "VIP Bronze", com.foxcash.domain.model.Money.of("39.90"),
                "Fotos", null, "#8E44AD", true, now);
        StoreButton button = StoreButton.create(store.id(), "Comprar", StoreButtonAction.BUY,
                null, 0, now);
        StoreMessage message = StoreMessage.create(store.id(), StoreMessageEvent.WELCOME,
                "Olá 👋 Bem-vindo à Bella Premium.", now);
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(store));
        when(planRepository.findActiveByStoreId(store.id())).thenReturn(List.of(plan));
        when(buttonRepository.findActiveByStoreIdOrderByPosition(store.id()))
                .thenReturn(List.of(button));
        when(messageRepository.findActiveByStoreId(store.id())).thenReturn(List.of(message));

        Storefront result = service.get(MERCHANT_ID);

        assertThat(result.store().name()).isEqualTo("Bella Premium");
        assertThat(result.store().logoUrl()).isEqualTo("https://logo/x.png");
        assertThat(result.store().color()).isEqualTo("#9B59B6");
        assertThat(result.activePlans()).hasSize(1);
        assertThat(result.activePlans().get(0).name()).isEqualTo("VIP Bronze");
        assertThat(result.activeButtons()).hasSize(1);
        assertThat(result.activeButtons().get(0).label()).isEqualTo("Comprar");
        assertThat(result.activeMessages()).hasSize(1);
        assertThat(result.activeMessages().get(0).event()).isEqualTo(StoreMessageEvent.WELCOME);
        assertThat(result.activeMessages().get(0).text()).contains("Bem-vindo");
    }

    @Test
    void getWithoutStoreThrows404() {
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MERCHANT_ID))
                .isInstanceOf(StoreNotFoundException.class);
    }
}
