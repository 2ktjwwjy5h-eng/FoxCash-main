package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.PixTransactionJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para transacoes Pix.
 */
public interface PixTransactionJpaRepository extends JpaRepository<PixTransactionJpaEntity, UUID>,
        JpaSpecificationExecutor<PixTransactionJpaEntity> {

    Optional<PixTransactionJpaEntity> findByExternalReference(String externalReference);

    /** Leitura com lock pessimista de escrita (processamento de webhook). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PixTransactionJpaEntity t where t.externalReference = :externalReference")
    Optional<PixTransactionJpaEntity> findByExternalReferenceForUpdate(
            @Param("externalReference") String externalReference);

    /** Leitura por id com lock pessimista de escrita (registro de chargeback). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PixTransactionJpaEntity t where t.id = :id")
    Optional<PixTransactionJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Retry idempotente (Spec 28): transação da chave por lojista (unique parcial). */
    Optional<PixTransactionJpaEntity> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    /** Transação da chave (Spec 125 — trace: mais recente; a chave é única por lojista). */
    Optional<PixTransactionJpaEntity> findFirstByIdempotencyKeyOrderByCreatedAtDesc(String idempotencyKey);

    /** Lista do lojista limitada (auditoria 2026-08-05: nunca carrega tudo). */
    List<PixTransactionJpaEntity> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    List<PixTransactionJpaEntity> findByMerchantIdAndStatusAndCreatedAtBetween(
            UUID merchantId, TransactionStatus status, Instant from, Instant to);

    long countByStatus(TransactionStatus status);

    long countByMerchantId(UUID merchantId);

    long countByMerchantIdAndStatus(UUID merchantId, TransactionStatus status);

    /** Cobranças órfãs (Spec 30): PENDING sem externalReference (timeout da Spec 28). */
    long countByStatusAndExternalReferenceIsNull(TransactionStatus status);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM PixTransactionJpaEntity t WHERE t.status = :status")
    BigDecimal sumAmountByStatus(@Param("status") TransactionStatus status);

    /** Volume aprovado do lojista (ganhos do dashboard master, Spec 15). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM PixTransactionJpaEntity t "
            + "WHERE t.merchantId = :merchantId AND t.status = :status")
    BigDecimal sumAmountByMerchantIdAndStatus(@Param("merchantId") UUID merchantId,
                                              @Param("status") TransactionStatus status);

    /** Provedor (gateway) da ultima cobranca do lojista (Spec 97 — panel; Spec 91). */
    @Query("SELECT t.provider FROM PixTransactionJpaEntity t "
            + "WHERE t.merchantId = :merchantId AND t.provider IS NOT NULL "
            + "ORDER BY t.createdAt DESC")
    List<String> findLastProviderByMerchantId(@Param("merchantId") UUID merchantId,
                                              Pageable pageable);

    /** Total de transacoes do lojista criadas no intervalo (Spec 25). */
    long countByMerchantIdAndCreatedAtBetween(UUID merchantId, Instant from, Instant to);

    /** Transacoes do lojista por status criadas no intervalo (Spec 25). */
    long countByMerchantIdAndStatusAndCreatedAtBetween(UUID merchantId, TransactionStatus status,
                                                       Instant from, Instant to);

    /** Volume aprovado do lojista no intervalo (Spec 25 — métricas de vendas). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM PixTransactionJpaEntity t "
            + "WHERE t.merchantId = :merchantId AND t.status = :status "
            + "AND t.createdAt BETWEEN :from AND :to")
    BigDecimal sumAmountByMerchantIdAndStatusAndCreatedAtBetween(
            @Param("merchantId") UUID merchantId,
            @Param("status") TransactionStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to);

    // ---- Spec 124: escopo por perfil de operação (filtro rígido do portal) ----

    /** Lista do lojista limitada e escopada por perfil (portal — recentes). */
    List<PixTransactionJpaEntity> findByMerchantIdAndProfileIdOrderByCreatedAtDesc(
            UUID merchantId, UUID profileId, Pageable pageable);

    /** Transações APROVADAS de um lojista+perfil no intervalo (dashboard). */
    List<PixTransactionJpaEntity> findByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
            UUID merchantId, UUID profileId, TransactionStatus status, Instant from, Instant to);

    /** Total de transações do lojista+perfil criadas no intervalo (conversão). */
    long countByMerchantIdAndProfileIdAndCreatedAtBetween(
            UUID merchantId, UUID profileId, Instant from, Instant to);

    /** Transações do lojista+perfil por status criadas no intervalo. */
    long countByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
            UUID merchantId, UUID profileId, TransactionStatus status, Instant from, Instant to);

    /** Volume aprovado do lojista+perfil no intervalo. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM PixTransactionJpaEntity t "
            + "WHERE t.merchantId = :merchantId AND t.profileId = :profileId "
            + "AND t.status = :status AND t.createdAt BETWEEN :from AND :to")
    BigDecimal sumAmountByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
            @Param("merchantId") UUID merchantId,
            @Param("profileId") UUID profileId,
            @Param("status") TransactionStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Vendas atribuídas aos bots do bot-set (métricas agregadas por perfil). */
    long countByBotIdInAndStatusAndCreatedAtBetween(List<UUID> botIds, TransactionStatus status,
                                                    Instant from, Instant to);

    /** Receita atribuída aos bots do bot-set (métricas agregadas por perfil). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM PixTransactionJpaEntity t "
            + "WHERE t.botId IN :botIds AND t.status = :status "
            + "AND t.createdAt BETWEEN :from AND :to")
    BigDecimal sumAmountByBotIdInAndStatusAndCreatedAtBetween(
            @Param("botIds") List<UUID> botIds,
            @Param("status") TransactionStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to);

    // ---- Spec 75: métricas por bot (venda = PAID não estornado) ----

    /** Vendas (transações PAID) de um bot no período (métricas por bot). */
    long countByBotIdAndStatusAndCreatedAtBetween(UUID botId, TransactionStatus status,
                                                  Instant from, Instant to);

    /** Receita (soma) de um bot no período — transações PAID. */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM PixTransactionJpaEntity t "
            + "WHERE t.botId = :botId AND t.status = :status "
            + "AND t.createdAt BETWEEN :from AND :to")
    BigDecimal sumAmountByBotIdAndStatusAndCreatedAtBetween(
            @Param("botId") UUID botId,
            @Param("status") TransactionStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Vendas atribuídas a bots de um lojista no período (portal agregado). */
    long countByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
            UUID merchantId, TransactionStatus status, Instant from, Instant to);

    /** Receita atribuída a bots de um lojista no período (portal agregado). */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM PixTransactionJpaEntity t "
            + "WHERE t.merchantId = :merchantId AND t.botId IS NOT NULL AND t.status = :status "
            + "AND t.createdAt BETWEEN :from AND :to")
    BigDecimal sumAmountByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(
            @Param("merchantId") UUID merchantId,
            @Param("status") TransactionStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * Backfill B-03 (auditoria 2026-08-05): registros com {@code pix_key} em
     * claro (sem prefixo {@code v1:}) para criptografar em repouso. Query NATIVA
     * devolvendo {@code Object[]} ({@code [id, pix_key]}) — carregar a entidade
     * aplicaria o {@code CryptoConverter} e quebraria na leitura do valor em claro.
     */
    @Query(nativeQuery = true, value = "SELECT id, pix_key FROM pix_transactions "
            + "WHERE pix_key IS NOT NULL AND pix_key NOT LIKE 'v1:%'")
    List<Object[]> findPlainPixKeys();

    // ---- Spec 107: extrato por provider (visão de gateways do portal) ----

    /**
     * Volume (Σ) e contagem (COUNT) de transações APPROVED do lojista no
     * intervalo, agrupados por {@code provider} (Spec 107 — extrato dos
     * adquirentes do portal). Cada linha é {@code Object[]} = {@code [provider,
     * volume (BigDecimal), count (Long)]} (padrão das queries agrupadas —
     * ver BotLeadJpaRepository). {@code provider} NULL (legado) é excluído.
     */
    @Query("SELECT t.provider, COALESCE(SUM(t.amount), 0), COUNT(t) "
            + "FROM PixTransactionJpaEntity t "
            + "WHERE t.merchantId = :merchantId AND t.status = :status "
            + "AND t.createdAt BETWEEN :from AND :to AND t.provider IS NOT NULL "
            + "GROUP BY t.provider")
    List<Object[]> sumAndCountApprovedByProvider(@Param("merchantId") UUID merchantId,
                                                 @Param("status") TransactionStatus status,
                                                 @Param("from") Instant from,
                                                 @Param("to") Instant to);

    /** Backfill B-03: grava a {@code pix_key} criptografada (converter aplicado no runner). */
    @Modifying
    @Query(nativeQuery = true, value = "UPDATE pix_transactions "
            + "SET pix_key = :pixKey, updated_at = :updatedAt WHERE id = :id")
    void encryptPixKey(@Param("id") UUID id, @Param("pixKey") String pixKey,
                       @Param("updatedAt") Instant updatedAt);
}
