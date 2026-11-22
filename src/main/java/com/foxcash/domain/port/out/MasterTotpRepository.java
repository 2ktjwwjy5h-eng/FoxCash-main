package com.foxcash.domain.port.out;

import com.foxcash.domain.model.master.MasterTotp;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: repositório do detalhe TOTP do método de MFA (Spec 44).
 * O {@code secretEncrypted} chega ao domínio já criptografado (AES-256/GCM via
 * CryptoConverter — decisão 2026-08-06) e só é decifrado em memória no momento
 * da verificação.
 */
public interface MasterTotpRepository {

    MasterTotp save(MasterTotp totp);

    Optional<MasterTotp> findByMethodId(UUID methodId);

    /** Leitura com lock pessimista de escrita (SEC-01): serializa o incremento
     * de {@code failed_attempts} entre verificações concorrentes — sem lost
     * update no lockout de TOTP. */
    Optional<MasterTotp> findByIdForUpdate(UUID methodId);
}
