package com.foxcash.domain.port.out;

import com.foxcash.domain.model.master.MasterSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de sessões do master (Spec 13).
 */
public interface MasterSessionRepository {

    MasterSession save(MasterSession session);

    Optional<MasterSession> findByTokenHash(String tokenHash);

    /**
     * Revoga (marca {@code revoked_at}) todas as sessões **ativas** do master —
     * usado na troca de senha (SEC-25, revisão de segurança 2026-08-11): a
     * rotação da credencial invalida as sessões existentes (mesmo padrão do
     * reset do lojista, Spec 40). Também limpa {@code stepped_up_at} (Spec 69 —
     * janelas de step-up morrem junto com as sessões revogadas).
     */
    void revokeAllByMasterId(UUID masterId, Instant now);

    /**
     * Registra a re-prova de posse (step-up MFA, Spec 69): seta
     * {@code stepped_up_at} na sessão ativa.
     */
    void markSteppedUp(UUID sessionId, Instant now);
}
