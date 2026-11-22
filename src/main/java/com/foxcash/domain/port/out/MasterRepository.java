package com.foxcash.domain.port.out;

import com.foxcash.domain.model.master.Master;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de perfis master (Spec 13; gestão Spec 41).
 */
public interface MasterRepository {

    Master save(Master master);

    Optional<Master> findById(UUID masterId);

    /** Leitura com lock pessimista de escrita (SEC-01): serializa o incremento
     * do contador de falhas de login entre requisições concorrentes — sem
     * lost update no estado de lockout. */
    Optional<Master> findByIdForUpdate(UUID masterId);

    Optional<Master> findByUsername(String username);

    /** Todos os perfis (gestão de perfis, Spec 41 — listar). */
    List<Master> findAll();

    long count();
}
