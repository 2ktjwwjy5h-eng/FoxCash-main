package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalSearchQuery;
import com.foxcash.domain.model.withdrawal.WithdrawalType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia de saidas (saques) do lojista (Spec 14).
 */
public interface WithdrawalRepository {

    Withdrawal save(Withdrawal withdrawal);

    Optional<Withdrawal> findById(UUID id);

    /**
     * SEC-22 (revisão de segurança 2026-08-11): saque por id sob lock pessimista.
     * O estorno de falha síncrona ({@code WithdrawalSettlementService#settleFailedByWithdrawalId})
     * precisa serializar com o webhook/reconciliação (que já usam FOR UPDATE) —
     * sem o lock, um settlement concorrente podia ser sobrescrito (estorno +
     * COMPLETED ao mesmo tempo = efeito financeiro duplicado).
     */
    Optional<Withdrawal> findByIdForUpdate(UUID id);

    /**
     * Lista os saques do lojista (mais recentes primeiro), limitada a
     * {@code limit} — auditoria 2026-08-05: nunca carrega o historico inteiro.
     */
    List<Withdrawal> findByMerchantId(UUID merchantId, int limit);

    /** Busca paginada com filtros (master — {@code GET /api/v1/master/withdrawals}). */
    PageResult<Withdrawal> search(WithdrawalSearchQuery query);

    /** Soma dos saques {@code COMPLETED} do lojista dentro do intervalo (Spec 22 — fluxo de caixa). */
    BigDecimal sumCompletedAmountByMerchantIdBetween(UUID merchantId, Instant from, Instant to);

    /**
     * Soma de {@code amount + fee} das saídas do {@code type} criadas desde
     * {@code since} (Spec 120 — teto diário do Pagamento via Saldo: TODAS as
     * tentativas do dia contabilizam, qualquer status). A consulta usa o índice
     * {@code ix_withdrawals_merchant_type_created} (V71).
     */
    BigDecimal sumAmountAndFeeByMerchantAndTypeCreatedSince(UUID merchantId, WithdrawalType type, Instant since);

    /** Saque por referencia externa sob lock pessimista (webhook de saida, Spec 36). */
    Optional<Withdrawal> findByExternalReferenceForUpdate(String externalReference);

    /** Saques {@code PROCESSING} orfaos mais antigos que o limite (reconciliacao, Spec 36). */
    List<Withdrawal> findProcessingOlderThan(Instant cutoff);
}
