package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreButtonNotFoundException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.exception.TooManyButtonsException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.model.store.StoreButtonAction;
import com.foxcash.domain.port.in.StoreButtonManagementUseCase;
import com.foxcash.domain.port.out.StoreButtonRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: gestão dos botões do menu da loja pelo portal (Spec 50) — o
 * vendedor monta o fluxo no painel e o bot apenas renderiza (Spec 52). Tudo
 * por {@code merchantId} do principal; a loja (1:1) é resolvida internamente
 * (404 cross-loja sem revelar existência). Auditoria
 * {@code BUTTON_CREATED}/{@code BUTTON_UPDATED}/{@code BUTTON_DELETED} (Spec 41)
 * na mesma transação — o payload nunca vai em claro para a trilha.
 */
@Service
public class StoreButtonService implements StoreButtonManagementUseCase {

    /** Máx. de botões do menu por loja (Spec 50) → 409 {@code too_many_buttons}. */
    public static final int MAX_BUTTONS = 20;

    private final StoreRepository storeRepository;
    private final StoreButtonRepository buttonRepository;
    private final StoreButtonPayloadCodec payloadCodec;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;

    public StoreButtonService(StoreRepository storeRepository,
                              StoreButtonRepository buttonRepository,
                              StoreButtonPayloadCodec payloadCodec,
                              PlatformActivityRecorder activityRecorder,
                              Clock clock) {
        this.storeRepository = storeRepository;
        this.buttonRepository = buttonRepository;
        this.payloadCodec = payloadCodec;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoreButton> list(UUID merchantId) {
        UUID storeId = requireStore(merchantId);
        return buttonRepository.findByStoreIdOrderByPosition(storeId);
    }

    @Override
    @Transactional(readOnly = true)
    public StoreButton get(UUID merchantId, UUID buttonId) {
        UUID storeId = requireStore(merchantId);
        return buttonRepository.findByIdAndStoreId(buttonId, storeId)
                .orElseThrow(StoreButtonNotFoundException::new);
    }

    @Override
    @Transactional
    public StoreButton create(UUID merchantId, SaveButtonCommand command) {
        UUID storeId = requireStore(merchantId);
        if (buttonRepository.countByStoreId(storeId) >= MAX_BUTTONS) {
            throw new TooManyButtonsException();
        }
        String payload = payloadCodec.validateAndEncode(command.action(), payloadMap(command.payloadJson()));
        int position = nextPosition(storeId);
        Instant now = clock.instant();
        StoreButton button = StoreButton.create(storeId, command.label(), command.action(),
                payload, position, now);
        StoreButton saved = buttonRepository.save(button);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.BUTTON_CREATED, "STORE_BUTTON", saved.id(), merchantId,
                Map.of("label", saved.label(), "action", saved.action()));
        return saved;
    }

    @Override
    @Transactional
    public StoreButton update(UUID merchantId, UUID buttonId, SaveButtonCommand command) {
        UUID storeId = requireStore(merchantId);
        StoreButton button = requireButton(storeId, buttonId);
        // Revalida o payload conforme o tipo (e re-serializa) quando o action
        // e/ou o payload mudam; campos nulos mantêm o estado atual.
        StoreButtonAction newAction = command.action() != null ? command.action() : button.action();
        String newPayloadJson = command.payloadJson() != null
                ? payloadCodec.validateAndEncode(newAction, payloadMap(command.payloadJson()))
                : button.payload();
        button.update(command.label(), newAction, newPayloadJson, command.active());
        StoreButton saved = buttonRepository.save(button);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.BUTTON_UPDATED, "STORE_BUTTON", saved.id(), merchantId,
                Map.of("label", saved.label(), "action", saved.action()));
        return saved;
    }

    @Override
    @Transactional
    public void delete(UUID merchantId, UUID buttonId) {
        UUID storeId = requireStore(merchantId);
        StoreButton button = requireButton(storeId, buttonId);
        buttonRepository.delete(button);
        reindexPositions(storeId);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.BUTTON_DELETED, "STORE_BUTTON", buttonId, merchantId,
                Map.of("label", button.label(), "action", button.action()));
    }

    @Override
    @Transactional
    public void reorder(UUID merchantId, List<UUID> orderedIds) {
        UUID storeId = requireStore(merchantId);
        if (orderedIds == null) {
            throw new IllegalArgumentException("ids[] e obrigatorio na reordenacao do menu");
        }
        List<StoreButton> buttons = buttonRepository.findByStoreIdOrderByPosition(storeId);
        if (orderedIds.size() != buttons.size()) {
            throw new IllegalArgumentException(
                    "ids[] deve conter exatamente os " + buttons.size() + " botoes da loja");
        }
        // Valida que todos os ids pertencem à loja (404 cross-loja — nunca
        // revela existência; ordem duplicada também é rejeitada).
        for (int i = 0; i < orderedIds.size(); i++) {
            UUID id = orderedIds.get(i);
            if (buttons.stream().noneMatch(b -> b.id().equals(id))) {
                throw new StoreButtonNotFoundException();
            }
            for (int j = i + 1; j < orderedIds.size(); j++) {
                if (orderedIds.get(j).equals(id)) {
                    throw new IllegalArgumentException("ids[] nao pode ter duplicados");
                }
            }
        }
        // Duas passadas para respeitar a unique (store_id, position): primeiro
        // desloca todos para posições temporárias livres (i + n), depois move
        // para a ordem final — sem colidir com a posição ainda ocupada.
        // saveAndFlush: cada UPDATE é aplicado imediatamente (sem flush o
        // Hibernate agruparia os UPDATEs e a unique seria violada na passada 2).
        int n = buttons.size();
        for (int i = 0; i < n; i++) {
            StoreButton button = buttons.get(i);
            button.moveTo(i + n);
            buttonRepository.saveAndFlush(button);
        }
        for (int i = 0; i < orderedIds.size(); i++) {
            UUID id = orderedIds.get(i);
            StoreButton button = buttons.stream().filter(b -> b.id().equals(id)).findFirst()
                    .orElseThrow(StoreButtonNotFoundException::new);
            button.moveTo(i);
            buttonRepository.saveAndFlush(button);
        }
    }

    private UUID requireStore(UUID merchantId) {
        return storeRepository.findByMerchantId(merchantId)
                .orElseThrow(StoreNotFoundException::new)
                .id();
    }

    private StoreButton requireButton(UUID storeId, UUID buttonId) {
        return buttonRepository.findByIdAndStoreId(buttonId, storeId)
                .orElseThrow(StoreButtonNotFoundException::new);
    }

    private int nextPosition(UUID storeId) {
        return buttonRepository.findByStoreIdOrderByPosition(storeId).stream()
                .mapToInt(StoreButton::position)
                .max()
                .orElse(-1) + 1;
    }

    /** Recalcula as posições 0..N após uma remoção (menu sem buracos). */
    private void reindexPositions(UUID storeId) {
        List<StoreButton> buttons = buttonRepository.findByStoreIdOrderByPosition(storeId);
        for (int i = 0; i < buttons.size(); i++) {
            StoreButton button = buttons.get(i);
            if (button.position() != i) {
                button.moveTo(i);
                buttonRepository.save(button);
            }
        }
    }

    /** O payload chega como Map (DTO) — serializa para o comando. */
    private Map<String, Object> payloadMap(String payloadJson) {
        return payloadCodec.decode(payloadJson);
    }
}
