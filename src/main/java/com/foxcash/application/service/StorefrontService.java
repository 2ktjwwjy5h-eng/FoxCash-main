package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.in.StorefrontQueryUseCase;
import com.foxcash.domain.port.out.PlanRepository;
import com.foxcash.domain.port.out.StoreButtonRepository;
import com.foxcash.domain.port.out.StoreMessageRepository;
import com.foxcash.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Aplicacao: consulta do storefront (Spec 48/49/50/51) — a fonte de verdade da
 * loja que os bots renderizam (Spec 52): identidade + planos ATIVOS (Spec 49,
 * card visual — SEM payload de ações, nunca em claro ao público) + botões do
 * menu ATIVOS em ordem (Spec 50, payload tipado — o bot renderiza o fluxo) +
 * mensagens por etapa ATIVAS (Spec 51, o bot renderiza no fluxo). 404
 * {@code store_not_found} sem loja.
 */
@Service
public class StorefrontService implements StorefrontQueryUseCase {

    private final StoreRepository storeRepository;
    private final PlanRepository planRepository;
    private final StoreButtonRepository buttonRepository;
    private final StoreMessageRepository messageRepository;

    public StorefrontService(StoreRepository storeRepository,
                             PlanRepository planRepository,
                             StoreButtonRepository buttonRepository,
                             StoreMessageRepository messageRepository) {
        this.storeRepository = storeRepository;
        this.planRepository = planRepository;
        this.buttonRepository = buttonRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Storefront get(UUID merchantId) {
        Store store = storeRepository.findByMerchantId(merchantId)
                .orElseThrow(StoreNotFoundException::new);
        return new Storefront(store,
                planRepository.findActiveByStoreId(store.id()),
                buttonRepository.findActiveByStoreIdOrderByPosition(store.id()),
                messageRepository.findActiveByStoreId(store.id()));
    }
}
