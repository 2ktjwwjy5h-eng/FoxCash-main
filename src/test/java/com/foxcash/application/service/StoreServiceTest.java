package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreAlreadyExistsException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.in.StoreManagementUseCase;
import com.foxcash.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do serviço de loja do portal (Spec 48): cria (409 se já existe),
 * consulta (404 sem loja) e atualiza (404 sem loja) — com auditoria
 * {@code STORE_CREATED}/{@code STORE_UPDATED} na mesma transação.
 */
class StoreServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private StoreService service;

    @BeforeEach
    void setUp() {
        service = new StoreService(storeRepository, activityRecorder, clock);
    }

    private StoreManagementUseCase.SaveStoreCommand command(String name) {
        return new StoreManagementUseCase.SaveStoreCommand(name, "https://logo.example/x.png",
                "https://banner.example/y.png", "Loja teste",
                List.of(new Store.SocialLink("Instagram", "https://instagram.com/loja")),
                "#9B59B6", "⭐", "Titulo", "Rodape");
    }

    @Test
    void createSavesAndRecordsActivity() {
        when(storeRepository.existsByMerchantId(MERCHANT_ID)).thenReturn(false);
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Store created = service.create(MERCHANT_ID, command("Bella Premium"));

        assertThat(created.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(created.name()).isEqualTo("Bella Premium");
        assertThat(created.color()).isEqualTo("#9B59B6");
        verify(storeRepository).save(any());
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.STORE_CREATED), eq("STORE"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void createWhenStoreExistsThrows409() {
        when(storeRepository.existsByMerchantId(MERCHANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.create(MERCHANT_ID, command("Bella")))
                .isInstanceOf(StoreAlreadyExistsException.class);

        verify(storeRepository, never()).save(any());
        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void getReturnsStore() {
        Store store = Store.create(MERCHANT_ID, "Bella", null, null, null, null, null, null, null, null, NOW);
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(store));

        Store result = service.get(MERCHANT_ID);

        assertThat(result.id()).isEqualTo(store.id());
    }

    @Test
    void getWithoutStoreThrows404() {
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MERCHANT_ID))
                .isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    void updateChangesFieldsAndRecordsActivity() {
        Store store = Store.create(MERCHANT_ID, "Bella", null, null, "Desc", null,
                "#9B59B6", null, null, null, NOW);
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(store));
        when(storeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Store updated = service.update(MERCHANT_ID,
                new StoreManagementUseCase.SaveStoreCommand("Bella Nova", null, null, null,
                        null, "#8E44AD", null, null, null));

        assertThat(updated.name()).isEqualTo("Bella Nova");
        assertThat(updated.description()).isEqualTo("Desc");
        assertThat(updated.color()).isEqualTo("#8E44AD");
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.STORE_UPDATED), eq("STORE"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void updateWithoutStoreThrows404() {
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(MERCHANT_ID,
                new StoreManagementUseCase.SaveStoreCommand("X", null, null, null, null, null, null, null, null)))
                .isInstanceOf(StoreNotFoundException.class);

        verify(storeRepository, never()).save(any());
    }
}
