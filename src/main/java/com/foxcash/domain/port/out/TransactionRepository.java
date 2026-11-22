package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.ProviderVolume;
import com.foxcash.domain.model.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia de transacoes Pix.
 */
public interface TransactionRepository {

    PixTransaction save(PixTransaction transaction);

    Optional<PixTransaction> findById(UUID transactionId);

    /**
     * Busca por id com lock pessimista de escrita (SELECT ... FOR UPDATE).
     * Usado no registro de chargeback para serializar registros concorrentes da
     * mesma transacao (evita 500 por violacao de unicidade — 409 canonico).
     */
    Optional<PixTransaction> findByIdForUpdate(UUID transactionId);

    /** Busca pela referencia externa do provedor (endToEndId no mock). */
    Optional<PixTransaction> findByExternalReference(String externalReference);

    /**
     * Busca pela referência externa com lock pessimista de escrita (SELECT ...
     * FOR UPDATE). Usado no processamento de webhook para serializar transições
     * concorrentes da mesma transação (evita crédito duplo no replay de PAID).
     */
    Optional<PixTransaction> findByExternalReferenceForUpdate(String externalReference);

    /**
     * Retry idempotente (Spec 28): busca a transação criada com a mesma chave de
     * idempotência do lojista — a unicidade é POR LOJISTA (lojistas diferentes
     * podem reusar a mesma chave).
     */
    Optional<PixTransaction> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    /** Transação da chave (Spec 125 — trace: lookup por idempotencyKey, mais recente). */
    Optional<PixTransaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Lista as transacoes do lojista (mais recentes primeiro), limitada a
     * {@code limit} — auditoria 2026-08-05: nunca carrega o historico inteiro.
     */
    List<PixTransaction> findByMerchantId(UUID merchantId, int limit);

    /**
     * Transacoes APROVADAS de um lojista dentro de um intervalo de tempo (UTC).
     */
    List<PixTransaction> findApprovedByMerchantIdBetween(UUID merchantId, Instant from, Instant to);

    /** Contagem de transacoes do lojista (monitoramento de risco, Spec 16). */
    long countByMerchantId(UUID merchantId);

    /** Contagem de transacoes do lojista por status (Spec 16). */
    long countByMerchantIdAndStatus(UUID merchantId, TransactionStatus status);

    /**
     * Cobranças órfãs (Spec 30): transações {@code PENDING} sem
     * {@code externalReference} — timeout da Spec 28 não recuperado pelo retry.
     * Visibilidade para o master (sem ação automática).
     */
    long countPendingWithoutExternalReference();

    /** Soma dos valores das transacoes do lojista por status (Spec 16). */
    BigDecimal sumAmountByMerchantIdAndStatus(UUID merchantId, TransactionStatus status);

    /**
     * Vendas do lojista paginadas com filtros opcionais (Spec 25): status,
     * janela de datas (from/to, UTC), page/size. Ordena por createdAt DESC.
     * {@code merchantId} e obrigatorio (escopo do lojista autenticado).
     * {@code profileId} (Spec 124) é opcional: null → lojista inteiro
     * (regressão — clientes sem header); presente → só o perfil (header
     * inválido → vazio, nunca lojista inteiro).
     */
    PageResult<PixTransaction> searchSales(UUID merchantId, UUID profileId,
                                           TransactionStatus status,
                                           Instant from, Instant to, int page, int size);

    /** Total de transacoes do lojista criadas no intervalo (Spec 25, conversao). */
    long countByMerchantIdAndCreatedAtBetween(UUID merchantId, Instant from, Instant to);

    /** Transacoes do lojista por status criadas no intervalo (Spec 25). */
    long countByMerchantIdAndStatusAndCreatedAtBetween(UUID merchantId, TransactionStatus status,
                                                       Instant from, Instant to);

    /** Soma dos valores das transacoes do lojista por status no intervalo (Spec 25). */
    BigDecimal sumAmountByMerchantIdAndStatusAndCreatedAtBetween(UUID merchantId,
                                                                TransactionStatus status,
                                                                Instant from, Instant to);

    // ---- Spec 124: escopo por perfil de operação (filtro rígido do portal) ----

    /** Lista do lojista limitada e escopada por perfil (portal — recentes). */
    List<PixTransaction> findByMerchantIdAndProfileId(UUID merchantId, UUID profileId, int limit);

    /** Transações APROVADAS de um lojista+perfil no intervalo (dashboard). */
    List<PixTransaction> findApprovedByMerchantIdBetweenAndProfileId(
            UUID merchantId, UUID profileId, Instant from, Instant to);

    /** Total de transações do lojista+perfil criadas no intervalo (conversão). */
    long countByMerchantIdAndProfileIdAndCreatedAtBetween(
            UUID merchantId, UUID profileId, Instant from, Instant to);

    /** Transações do lojista+perfil por status criadas no intervalo. */
    long countByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
            UUID merchantId, UUID profileId, TransactionStatus status, Instant from, Instant to);

    /** Volume aprovado do lojista+perfil no intervalo. */
    BigDecimal sumAmountByMerchantIdAndProfileIdAndStatusAndCreatedAtBetween(
            UUID merchantId, UUID profileId, TransactionStatus status, Instant from, Instant to);

    /** Vendas atribuídas aos bots do bot-set (métricas agregadas por perfil). */
    long countByBotIdInAndStatusAndCreatedAtBetween(List<UUID> botIds, TransactionStatus status,
                                                    Instant from, Instant to);

    /** Receita atribuída aos bots do bot-set (métricas agregadas por perfil). */
    BigDecimal sumAmountByBotIdInAndStatusAndCreatedAtBetween(List<UUID> botIds,
                                                             TransactionStatus status,
                                                             Instant from, Instant to);

    // ---- Spec 75: métricas por bot (venda = PAID não estornado) ----

    /** Vendas (transações PAID) de um bot no período (métricas por bot). */
    long countByBotIdAndStatusAndCreatedAtBetween(UUID botId, TransactionStatus status,
                                                  Instant from, Instant to);

    /** Receita (soma) de um bot no período — transações PAID. */
    BigDecimal sumAmountByBotIdAndStatusAndCreatedAtBetween(UUID botId, TransactionStatus status,
                                                           Instant from, Instant to);

    /** Vendas atribuídas a bots de um lojista no período (portal agregado). */
    long countByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(UUID merchantId,
                                                                       TransactionStatus status,
                                                                       Instant from, Instant to);

    /** Receita atribuída a bots de um lojista no período (portal agregado). */
    BigDecimal sumAmountByMerchantIdAndBotIdIsNotNullAndStatusAndCreatedAtBetween(UUID merchantId,
                                                                                TransactionStatus status,
                                                                                Instant from, Instant to);

    // ---- Spec 107: extrato por provider (visão de gateways do portal) ----

    /**
     * Volume e contagem de transações APPROVED do lojista no intervalo,
     * agrupados por {@code provider} (Spec 107 — extrato dos adquirentes).
     * {@code provider} NULL (legado) é excluído. Lista vazia quando não há
     * transações no período.
     */
    List<ProviderVolume> sumAndCountApprovedByProvider(UUID merchantId, Instant from, Instant to);
}
