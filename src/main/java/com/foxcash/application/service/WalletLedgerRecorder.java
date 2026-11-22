package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Grava uma entry do ledger do wallet (Spec 27) — chamado DENTRO do
 * {@code @Transactional} do fluxo que muta o saldo (webhook/chargeback/saque).
 *
 * <p>{@code balanceAfter} é o saldo do merchant APÓS a mutação (já aplicada
 * pelo chamador). A entry é imutável e única por (tipo, origem) — a constraint
 * {@code uq_wallet_ledger_ref} é o backstop contra gravação duplicada.
 */
@Service
public class WalletLedgerRecorder {

    private final WalletLedgerRepository repository;

    public WalletLedgerRecorder(WalletLedgerRepository repository) {
        this.repository = repository;
    }

    /**
     * Grava uma entry com o saldo resultante pós-mutação.
     *
     * @param merchant       lojista (JÁ mutado pelo chamador)
     * @param type           tipo da movimentação
     * @param amount         valor da movimentação (sinal implícito do tipo)
     * @param referenceType  origem da movimentação (TRANSACTION/WITHDRAWAL/CHARGEBACK)
     * @param referenceId    id da origem (transação/saque/chargeback)
     */
    public WalletLedgerEntry record(Merchant merchant, WalletLedgerEntryType type,
                                    Money amount, String referenceType, UUID referenceId) {
        WalletLedgerEntry entry = WalletLedgerEntry.record(
                merchant.id(), type, amount, merchant.walletBalance(),
                referenceType, referenceId, Instant.now());
        return repository.save(entry);
    }

    /**
     * Cobranca CUMULATIVA (Spec 33): a fatura/receivable e cobrada em MULTIPLAS
     * etapas (pagamentos parciais), mas o ledger mantem UMA entry por origem
     * ({@code uq_wallet_ledger_ref}). Na primeira cobranca insere a entry; nas
     * re-cobrancas acumula o total no {@code amount} e ajusta o
     * {@code balance_after} para o saldo pos-mutacao (id/created_at intactos).
     */
    public WalletLedgerEntry recordCumulative(Merchant merchant, WalletLedgerEntryType type,
                                              Money debit, String referenceType, UUID referenceId) {
        Optional<WalletLedgerEntry> existing = repository.findByReference(
                merchant.id(), type, referenceType, referenceId);
        if (existing.isEmpty()) {
            return record(merchant, type, debit, referenceType, referenceId);
        }
        Money cumulative = existing.get().amount().add(debit);
        return repository.updateAmountAndBalance(existing.get().id(),
                cumulative.amount(), merchant.walletBalance().amount());
    }
}
