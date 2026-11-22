package com.foxcash.domain.port.in;

import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.model.store.StoreMessage;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: consulta do storefront (Spec 48/49/50/51) — a configuracao
 * completa da loja que os bots renderizam (Spec 52): identidade + planos
 * ATIVOS (Spec 49) + botões do menu ATIVOS em ordem (Spec 50) + mensagens por
 * etapa ATIVAS (Spec 51). 404 {@code store_not_found} sem loja.
 */
public interface StorefrontQueryUseCase {

    Storefront get(UUID merchantId);

    /** Identidade + planos ativos (sem payload de ações — nunca em claro) + botões + mensagens ativas. */
    record Storefront(Store store, List<Plan> activePlans, List<StoreButton> activeButtons,
                      List<StoreMessage> activeMessages) {
    }
}
