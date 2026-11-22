package com.foxcash.domain.port.out;

import com.foxcash.domain.model.fingerprint.DeviceFingerprint;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de fingerprints de dispositivo (Spec 141).
 *
 * <p>Escrita upsert atômica (ON CONFLICT) — incrementa {@code seen_count} e
 * {@code distinct_merchants} sem lost update entre requisições concorrentes
 * (o mesmo hash pode chegar em paralelo no cadastro/login).
 */
public interface DeviceFingerprintRepository {

    /**
     * Registra a ocorrência de um hash: cria o fingerprint se novo ou atualiza
     * as janelas/contadores; grava/atualiza a associação hash→lojista.
     *
     * @param fingerprintHash SHA-256 hex (64 chars) já validado pelo chamador
     * @param merchantId      lojista que gerou o fingerprint (cadastro/login)
     * @param now             timestamp do evento
     * @return snapshot atualizado do fingerprint (para a trilha de auditoria)
     */
    DeviceFingerprint record(String fingerprintHash, UUID merchantId, Instant now);

    /** Snapshot atual do fingerprint (vazio quando nunca registrado). */
    Optional<DeviceFingerprint> findByHash(String fingerprintHash);

    /**
     * Maior {@code distinct_merchants} entre os fingerprints do lojista (0 =
     * sem fingerprint registrado, 1 = device exclusivo, N&gt;1 = mesmo device
     * em N contas — sinal de risco). Usado pelo painel do master (Spec 141).
     */
    int maxDistinctMerchantsOf(UUID merchantId);
}
