package com.foxcash.domain.port.out;

import com.foxcash.domain.model.master.MasterMfaMethod;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: repositório de métodos de MFA do master (Spec 44).
 */
public interface MasterMfaMethodRepository {

    MasterMfaMethod save(MasterMfaMethod method);

    Optional<MasterMfaMethod> findActiveByMasterId(UUID masterId);

    Optional<MasterMfaMethod> findPendingByMasterId(UUID masterId);

    Optional<MasterMfaMethod> findByIdAndMasterId(UUID id, UUID masterId);

    /** Remove métodos do master (desativação). */
    void deleteByMasterId(UUID masterId);
}
