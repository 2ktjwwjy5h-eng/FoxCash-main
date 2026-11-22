package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência do ledger do wallet (Spec 27). Append-only —
 * apenas INSERT e consultas; nunca update/delete.
 *
 * <p>Exceção documentada (Spec 33): a cobrança de fatura é MULTI-ETAPA
 * (pagamentos parciais) e mantém UMA entry por fatura/receivable — a
 * {@code uq_wallet_ledger_ref} proíbe mais de uma. A re-cobrança
 * {@code updateAmountAndBalance} acumula o total cobrado na entry existente
 * (amount cumulativo + balance_after atual), preservando a imutabilidade
 * estrutural da origem (id/referência/created_at nunca mudam).
 */
public interface WalletLedgerRepository {

    WalletLedgerEntry save(WalletLedgerEntry entry);

    /** Já existe alguma entry para o lojista (usado pelo backfill de abertura). */
    boolean existsByMerchantId(UUID merchantId);

    /** Entry unica por (merchant, tipo, origem) — usada na cobranca cumulativa (Spec 33). */
    Optional<WalletLedgerEntry> findByReference(UUID merchantId, WalletLedgerEntryType type,
                                                String referenceType, UUID referenceId);

    /** Todas as entries de uma origem (Spec 125 — trace: movimentação da transação). */
    List<WalletLedgerEntry> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);

    /**
     * Atualiza amount/balance_after de uma entry existente (Spec 33): a
     * re-cobranca de fatura/receivable acumula o total no amount e ajusta o
     * balance_after para o saldo pos-mutacao — id/created_at permanecem.
     */
    WalletLedgerEntry updateAmountAndBalance(UUID entryId, BigDecimal amount, BigDecimal balanceAfter);

    /** Movimentações do lojista paginadas com filtros opcionais (type/período). */
    PageResult<WalletLedgerEntry> findByMerchantId(UUID merchantId,
                                                   WalletLedgerEntryType type,
                                                   Instant from, Instant to,
                                                   int page, int size);

    /** Soma das movimentações do lojista (backstop da Spec 30). */
    BigDecimal sumAmountByMerchantId(UUID merchantId);

    /** Última entry do lojista (balanço de referência da Spec 30). */
    Optional<WalletLedgerEntry> findLatestByMerchantId(UUID merchantId);
}
