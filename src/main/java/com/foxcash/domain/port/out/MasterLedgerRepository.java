package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.revenue.MasterLedgerEntry;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia do ledger do master (Spec 34). Append-only —
 * apenas INSERT e consultas; nunca update/delete.
 *
 * <p>Excecao documentada (mesmo padrao da Spec 27/33): a recuperacao de
 * receivable de estorno pode acontecer em multiplas etapas (pagamento parcial)
 * mantendo UMA entry por receivable — a {@code uq_master_ledger_ref} proibe
 * mais de uma. A re-cobranca {@code updateAmountAndBalance} acumula o total
 * recebido na entry existente (amount cumulativo + balance_after atual).
 */
public interface MasterLedgerRepository {

    MasterLedgerEntry save(MasterLedgerEntry entry);

    /** Entry unica por (lojista, tipo, origem) — usada na recovery cumulativa. */
    Optional<MasterLedgerEntry> findByReference(UUID merchantId, MasterLedgerEntryType type,
                                                String referenceType, UUID referenceId);

    /** Todas as entries de uma origem (Spec 125 — trace: receita da plataforma). */
    List<MasterLedgerEntry> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);

    /** Acumula amount/balance_after de uma entry existente (recovery parcial). */
    MasterLedgerEntry updateAmountAndBalance(UUID entryId, BigDecimal amount, BigDecimal balanceAfter);

    /** Movimentacoes paginadas com filtros opcionais (lojista/tipo/período). */
    PageResult<MasterLedgerEntry> findByMerchantId(UUID merchantId,
                                                   MasterLedgerEntryType type,
                                                   Instant from, Instant to,
                                                   int page, int size);

    /** Soma das entries de um lojista por tipo no período (summary da Spec 34). */
    BigDecimal sumAmountByMerchantIdAndTypeBetween(UUID merchantId,
                                                   MasterLedgerEntryType type,
                                                   Instant from, Instant to);

    /** Soma global (todos os lojistas) por tipo no período — summary da Spec 34. */
    BigDecimal sumAmountByTypeBetween(MasterLedgerEntryType type, Instant from, Instant to);

    /** Última entry do lojista (saldo de referencia para balance_after). */
    Optional<MasterLedgerEntry> findLatestByMerchantId(UUID merchantId);
}
