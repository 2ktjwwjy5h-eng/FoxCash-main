package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.infrastructure.outbound.persistence.entity.DeviceFingerprintJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.entity.DeviceFingerprintMerchantJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data (infraestrutura) para fingerprints de dispositivo
 * (Spec 141). Escrita upsert atômica via SQL nativo {@code ON CONFLICT} —
 * incrementa contadores sem lost update entre requisições concorrentes.
 */
public interface DeviceFingerprintJpaRepository extends
        JpaRepository<DeviceFingerprintJpaEntity, String> {

    /**
     * Upsert do fingerprint: cria se novo, senão atualiza last_seen e incrementa
     * o contador. {@code distinct_merchants} é recalculado após a associação.
     */
    @Modifying
    @Query(value = """
            INSERT INTO device_fingerprints (fingerprint_hash, first_seen_at, last_seen_at, seen_count, distinct_merchants)
            VALUES (:hash, :now, :now, 1, 1)
            ON CONFLICT (fingerprint_hash) DO UPDATE SET
                last_seen_at = :now,
                seen_count   = device_fingerprints.seen_count + 1
            """, nativeQuery = true)
    void upsertFingerprint(@Param("hash") String hash, @Param("now") Instant now);

    /** Upsert da associação hash→lojista (1ª vez = novo par; senão incrementa). */
    @Modifying
    @Query(value = """
            INSERT INTO device_fingerprint_merchants (fingerprint_hash, merchant_id, first_seen_at, last_seen_at, seen_count)
            VALUES (:hash, :merchantId, :now, :now, 1)
            ON CONFLICT (fingerprint_hash, merchant_id) DO UPDATE SET
                last_seen_at = :now,
                seen_count   = device_fingerprint_merchants.seen_count + 1
            """, nativeQuery = true)
    void upsertMerchantLink(@Param("hash") String hash, @Param("merchantId") UUID merchantId,
                            @Param("now") Instant now);

    /** Recalcula quantos lojistas distintos o dispositivo tocou (após upsert). */
    @Modifying
    @Query(value = """
            UPDATE device_fingerprints f
               SET distinct_merchants = (SELECT count(*) FROM device_fingerprint_merchants m
                                          WHERE m.fingerprint_hash = f.fingerprint_hash)
             WHERE f.fingerprint_hash = :hash
            """, nativeQuery = true)
    void refreshDistinctMerchants(@Param("hash") String hash);

    /** Quantos lojistas distintos um fingerprint já tocou (consulta de risco). */
    @Query(value = "SELECT count(*) FROM device_fingerprint_merchants WHERE fingerprint_hash = :hash",
            nativeQuery = true)
    long countDistinctMerchants(@Param("hash") String hash);

    /** Associações de um hash (Spec 141 — auditoria: prova do upsert). */
    @Query("SELECT m FROM DeviceFingerprintMerchantJpaEntity m WHERE m.id.fingerprintHash = :hash")
    List<DeviceFingerprintMerchantJpaEntity> findByIdFingerprintHash(@Param("hash") String fingerprintHash);

    /**
     * Maior distinct_merchants entre os fingerprints do lojista (0 = nenhum) —
     * join na associação, max sobre a tabela de fingerprints (Spec 141).
     */
    @Query(value = """
            SELECT COALESCE(MAX(f.distinct_merchants), 0)
              FROM device_fingerprints f
              JOIN device_fingerprint_merchants m ON m.fingerprint_hash = f.fingerprint_hash
             WHERE m.merchant_id = :merchantId
            """, nativeQuery = true)
    int maxDistinctMerchantsOf(@Param("merchantId") UUID merchantId);
}
