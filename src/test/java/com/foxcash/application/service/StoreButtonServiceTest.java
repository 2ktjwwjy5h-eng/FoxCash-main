package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreButtonNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.exception.TooManyButtonsException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.model.store.StoreButtonAction;
import com.foxcash.domain.port.in.StoreButtonManagementUseCase;
import com.foxcash.domain.port.out.StoreButtonRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do serviço de botões do menu do portal (Spec 50): lista/consulta/cria
 * (limite 20 → 409)/atualiza/remove (reindexa posições)/reordena — com auditoria
 * {@code BUTTON_CREATED/UPDATED/DELETED} na mesma transação e payload nunca em
 * claro na trilha.
 */
class StoreButtonServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();

    private final StoreRepository storeRepository = mock(StoreRepository.class);
    private final StoreButtonRepository buttonRepository = mock(StoreButtonRepository.class);
    private final StoreButtonPayloadCodec payloadCodec = new StoreButtonPayloadCodec(
            new com.fasterxml.jackson.databind.ObjectMapper());
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private StoreButtonService service;

    @BeforeEach
    void setUp() {
        service = new StoreButtonService(storeRepository, buttonRepository, payloadCodec,
                activityRecorder, clock);
        when(storeRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(Store.restore(STORE_ID, MERCHANT_ID, "Loja", null, null,
                        null, null, null, null, null, null, NOW, NOW)));
    }

    private StoreButtonManagementUseCase.SaveButtonCommand buyCommand(String label) {
        return new StoreButtonManagementUseCase.SaveButtonCommand(label,
                StoreButtonAction.BUY, null, null);
    }

    @Test
    void listReturnsButtonsOrderedByPosition() {
        StoreButton b0 = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY, null, 0, NOW);
        StoreButton b1 = StoreButton.create(STORE_ID, "Instagram", StoreButtonAction.SOCIAL,
                "{\"url\":\"https://ig.com/x\"}", 1, NOW);
        when(buttonRepository.findByStoreIdOrderByPosition(STORE_ID)).thenReturn(List.of(b0, b1));

        List<StoreButton> result = service.list(MERCHANT_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).label()).isEqualTo("Comprar");
        assertThat(result.get(1).label()).isEqualTo("Instagram");
    }

    @Test
    void getReturnsButtonOfStore() {
        StoreButton button = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY, null, 0, NOW);
        when(buttonRepository.findByIdAndStoreId(button.id(), STORE_ID))
                .thenReturn(Optional.of(button));

        StoreButton result = service.get(MERCHANT_ID, button.id());

        assertThat(result.id()).isEqualTo(button.id());
    }

    @Test
    void getWithoutButtonThrows404() {
        UUID buttonId = UUID.randomUUID();
        when(buttonRepository.findByIdAndStoreId(buttonId, STORE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(MERCHANT_ID, buttonId))
                .isInstanceOf(StoreButtonNotFoundException.class);
    }

    @Test
    void createAppendsAtEndAndRecordsActivity() {
        StoreButton existing = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY, null, 0, NOW);
        when(buttonRepository.countByStoreId(STORE_ID)).thenReturn(1L);
        when(buttonRepository.findByStoreIdOrderByPosition(STORE_ID)).thenReturn(List.of(existing));
        when(buttonRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreButton created = service.create(MERCHANT_ID, buyCommand("FAQ"));

        assertThat(created.position()).isEqualTo(1);
        assertThat(created.label()).isEqualTo("FAQ");
        assertThat(created.active()).isTrue();
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BUTTON_CREATED), eq("STORE_BUTTON"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void createOverLimitThrows409() {
        when(buttonRepository.countByStoreId(STORE_ID)).thenReturn((long) StoreButtonService.MAX_BUTTONS);

        assertThatThrownBy(() -> service.create(MERCHANT_ID, buyCommand("X")))
                .isInstanceOf(TooManyButtonsException.class);

        verify(buttonRepository, never()).save(any());
        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createWithoutStoreThrows404() {
        when(storeRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(MERCHANT_ID, buyCommand("X")))
                .isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    void updateChangesFieldsAndRecordsActivity() {
        StoreButton button = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY, null, 0, NOW);
        when(buttonRepository.findByIdAndStoreId(button.id(), STORE_ID)).thenReturn(Optional.of(button));
        when(buttonRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoreButton updated = service.update(MERCHANT_ID, button.id(),
                new StoreButtonManagementUseCase.SaveButtonCommand("Instagram",
                        StoreButtonAction.SOCIAL, "{\"url\":\"https://ig.com/x\"}", null));

        assertThat(updated.label()).isEqualTo("Instagram");
        assertThat(updated.action()).isEqualTo(StoreButtonAction.SOCIAL);
        // L96: payloadJson fornecido → payload é RE-serializado (mutante negaria o
        // ternário e manteria o payload antigo, null).
        assertThat(updated.payload()).contains("ig.com");
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BUTTON_UPDATED), eq("STORE_BUTTON"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void updateWithoutButtonThrows404() {
        UUID buttonId = UUID.randomUUID();
        when(buttonRepository.findByIdAndStoreId(buttonId, STORE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(MERCHANT_ID, buttonId, buyCommand("X")))
                .isInstanceOf(StoreButtonNotFoundException.class);
    }

    @Test
    void deleteRemovesAndReindexesPositions() {
        StoreButton b0 = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY, null, 0, NOW);
        StoreButton b1 = StoreButton.create(STORE_ID, "Instagram", StoreButtonAction.SOCIAL,
                "{\"url\":\"https://ig.com/x\"}", 1, NOW);
        StoreButton b2 = StoreButton.create(STORE_ID, "FAQ", StoreButtonAction.FAQ,
                "{\"text\":\"Duvidas?\"}", 2, NOW);
        when(buttonRepository.findByIdAndStoreId(b1.id(), STORE_ID)).thenReturn(Optional.of(b1));
        when(buttonRepository.findByStoreIdOrderByPosition(STORE_ID)).thenReturn(List.of(b0, b2));

        service.delete(MERCHANT_ID, b1.id());

        verify(buttonRepository).delete(b1);
        // b2 (position 2) é reposicionado para 1 (reindexação 0..N). O captor
        // garante que o botão salvo é o b2 na posição 1 (L187 negate/L188 void
        // deixariam b0 ou b2 em posição errada).
        ArgumentCaptor<StoreButton> reindexed = ArgumentCaptor.forClass(StoreButton.class);
        verify(buttonRepository, times(1)).save(reindexed.capture());
        assertThat(reindexed.getValue().id()).isEqualTo(b2.id());
        assertThat(reindexed.getValue().position()).isEqualTo(1);
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BUTTON_DELETED), eq("STORE_BUTTON"), any(), eq(MERCHANT_ID), any());
    }

    @Test
    void reorderRecalculatesPositions() {
        StoreButton b0 = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY, null, 0, NOW);
        StoreButton b1 = StoreButton.create(STORE_ID, "Instagram", StoreButtonAction.SOCIAL,
                "{\"url\":\"https://ig.com/x\"}", 1, NOW);
        when(buttonRepository.findByStoreIdOrderByPosition(STORE_ID)).thenReturn(List.of(b0, b1));
        // Grava a posição de CADA saveAndFlush para verificar as duas passadas:
        // 1ª desloca p/ posições temporárias (i+n) → 2,3; 2ª move p/ ordem final → 0,1.
        java.util.List<Integer> savedPositions = new java.util.ArrayList<>();
        when(buttonRepository.saveAndFlush(any())).thenAnswer(inv -> {
            savedPositions.add(((StoreButton) inv.getArgument(0)).position());
            return inv.getArgument(0);
        });

        service.reorder(MERCHANT_ID, List.of(b1.id(), b0.id()));

        assertThat(b0.position()).isEqualTo(1);
        assertThat(b1.position()).isEqualTo(0);
        // L152: se o moveTo(i+n) da 1ª passada for removido, as posições salvas
        // seriam [0,1,0,1] (sem deslocamento temporário) → falha aqui.
        assertThat(savedPositions).containsExactly(2, 3, 0, 1);
        // 2 passadas (deslocar i+n + posição final) × 2 botões = 4 saveAndFlush.
        verify(buttonRepository, times(4)).saveAndFlush(any());
    }

    @Test
    void reorderRejectsUnknownId() {
        StoreButton b0 = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY, null, 0, NOW);
        when(buttonRepository.findByStoreIdOrderByPosition(STORE_ID)).thenReturn(List.of(b0));

        assertThatThrownBy(() -> service.reorder(MERCHANT_ID,
                List.of(UUID.randomUUID())))
                .isInstanceOf(StoreButtonNotFoundException.class);
        // L135: a validação de pertencimento NÃO pode deixar passar id desconhecido
        // até o deslocamento — nenhum saveAndFlush pode ter ocorrido.
        verify(buttonRepository, never()).saveAndFlush(any());
    }

    @Test
    void reorderRejectsWrongSizeOrDuplicates() {
        StoreButton b0 = StoreButton.create(STORE_ID, "Comprar", StoreButtonAction.BUY, null, 0, NOW);
        StoreButton b1 = StoreButton.create(STORE_ID, "Instagram", StoreButtonAction.SOCIAL,
                "{\"url\":\"https://ig.com/x\"}", 1, NOW);
        when(buttonRepository.findByStoreIdOrderByPosition(STORE_ID)).thenReturn(List.of(b0, b1));

        assertThatThrownBy(() -> service.reorder(MERCHANT_ID, List.of(b0.id())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reorder(MERCHANT_ID, List.of(b0.id(), b0.id())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createWithInvalidPayloadThrows() {
        when(buttonRepository.countByStoreId(STORE_ID)).thenReturn(0L);
        when(buttonRepository.findByStoreIdOrderByPosition(STORE_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(MERCHANT_ID,
                new StoreButtonManagementUseCase.SaveButtonCommand("FAQ",
                        StoreButtonAction.FAQ, "{}", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
