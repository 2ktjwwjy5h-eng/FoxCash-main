package com.foxcash.domain.port.in;

import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.model.store.StoreButtonAction;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: gestão dos botões do menu da loja pelo portal (Spec 50),
 * {@code ROLE_MERCHANT_PORTAL}. Tudo por {@code merchantId} do principal — a
 * loja (1:1) é resolvida internamente; nunca por id solto (404 cross-loja).
 *
 * <p>O menu é 100% configurável: o vendedor escolhe quais botões e em que
 * ordem (até 20 — 409 {@code too_many_buttons}); a reordenação recalcula as
 * {@code position}s 0..N. O payload é validado por tipo e criptografado em
 * repouso.
 */
public interface StoreButtonManagementUseCase {

    /** Botões da loja em ordem de {@code position} (menu do portal). */
    List<StoreButton> list(UUID merchantId);

    /** 404 {@code button_not_found} se não existe na loja do lojista. */
    StoreButton get(UUID merchantId, UUID buttonId);

    /** Cria no fim do menu (201; position = max+1; 409 {@code too_many_buttons} > 20). */
    StoreButton create(UUID merchantId, SaveButtonCommand command);

    /** Atualiza label/action/payload/active (campos nulos = não alterar). */
    StoreButton update(UUID merchantId, UUID buttonId, SaveButtonCommand command);

    /** 204 — remove e recalcula as posições restantes (0..N). */
    void delete(UUID merchantId, UUID buttonId);

    /**
     * Reordena o menu (PUT /order {@code {ids[]}}) — valida que todos os ids
     * pertencem à loja e recalcula as posições 0..N na ordem informada.
     */
    void reorder(UUID merchantId, List<UUID> orderedIds);

    /** Botão do menu: label + action + payload JSON (validado por tipo). */
    record SaveButtonCommand(String label, StoreButtonAction action, String payloadJson,
                             Boolean active) {
    }
}
