package com.foxcash.application.service;

import com.foxcash.domain.exception.StoreAlreadyExistsException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.in.StoreManagementUseCase;
import com.foxcash.domain.port.out.StoreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicacao: gestao da loja do vendedor pelo portal (Spec 48) — cria (409 se
 * ja existe), consulta e atualiza a loja do principal. Cada mutacao e
 * registrada na trilha unificada de atividades (Spec 41:
 * {@code STORE_CREATED}/{@code STORE_UPDATED}) na mesma transacao.
 */
@Service
public class StoreService implements StoreManagementUseCase {

    private final StoreRepository storeRepository;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;

    public StoreService(StoreRepository storeRepository,
                        PlatformActivityRecorder activityRecorder,
                        Clock clock) {
        this.storeRepository = storeRepository;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Store get(UUID merchantId) {
        return requireStore(merchantId);
    }

    @Override
    @Transactional
    public Store create(UUID merchantId, SaveStoreCommand command) {
        if (storeRepository.existsByMerchantId(merchantId)) {
            throw new StoreAlreadyExistsException();
        }
        Instant now = clock.instant();
        Store store = Store.create(merchantId, command.name(), command.logoUrl(),
                command.bannerUrl(), command.description(), command.socialLinks(),
                command.color(), command.emoji(), command.title(), command.footer(), now);
        Store saved = storeRepository.save(store);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.STORE_CREATED, "STORE", saved.id(), merchantId,
                Map.of("name", saved.name()));
        return saved;
    }

    @Override
    @Transactional
    public Store update(UUID merchantId, SaveStoreCommand command) {
        Store store = requireStore(merchantId);
        store.update(command.name(), command.logoUrl(), command.bannerUrl(),
                command.description(), command.socialLinks(), command.color(),
                command.emoji(), command.title(), command.footer(), clock.instant());
        Store saved = storeRepository.save(store);
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.STORE_UPDATED, "STORE", saved.id(), merchantId,
                Map.of("name", saved.name()));
        return saved;
    }

    private Store requireStore(UUID merchantId) {
        return storeRepository.findByMerchantId(merchantId)
                .orElseThrow(StoreNotFoundException::new);
    }
}
