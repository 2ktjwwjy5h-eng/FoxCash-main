package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.revenue.MasterLedgerEntry;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.port.out.MasterLedgerRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Grava uma entry do ledger do master (Spec 34) — chamado DENTRO do
 * {@code @Transactional} do fluxo que origina receita/perda da plataforma
 * (fatura paga, receivable pago, saque administrativo, write-off).
 *
 * <p>{@code balanceAfter} e o saldo da plataforma para aquele lojista apos a
 * entry (ultimo saldo + amount) — serializado pelo lock do merchant que os
 * fluxos origem ja adquirem (mesma garantia da Spec 27). A entry e imutavel e
 * unica por (lojista, tipo, origem): a constraint {@code uq_master_ledger_ref}
 * e o backstop contra gravação duplicada.
 */
@Service
public class MasterLedgerRecorder {

    private final MasterLedgerRepository repository;

    public MasterLedgerRecorder(MasterLedgerRepository repository) {
        this.repository = repository;
    }

    /**
     * Grava uma entry com balance_after = ultimo saldo do lojista + amount.
     *
     * @param merchantId     lojista origem do dinheiro
     * @param type           tipo da movimentacao
     * @param amount         valor da movimentacao (sinal implicito do tipo)
     * @param referenceType  origem (INVOICE/RECEIVABLE/WITHDRAWAL)
     * @param referenceId    id da origem (fatura/receivable/saque)
     */
    public MasterLedgerEntry record(UUID merchantId, MasterLedgerEntryType type,
                                    Money amount, String referenceType, UUID referenceId) {
        MasterLedgerEntry entry = MasterLedgerEntry.record(
                merchantId, type, amount, balanceAfter(merchantId, amount),
                referenceType, referenceId, Instant.now());
        return repository.save(entry);
    }

    /**
     * Grava CUMULATIVO (Spec 34): a recuperacao de receivable de estorno pode
     * acontecer em multiplas etapas (pagamento parcial), mantendo UMA entry por
     * receivable ({@code uq_master_ledger_ref}). Na primeira recuperacao insere;
     * nas seguintes acumula o total no {@code amount} e ajusta o
     * {@code balance_after} (id/created_at intactos).
     */
    public MasterLedgerEntry recordCumulative(UUID merchantId, MasterLedgerEntryType type,
                                              Money amount, String referenceType, UUID referenceId) {
        Optional<MasterLedgerEntry> existing = repository.findByReference(
                merchantId, type, referenceType, referenceId);
        if (existing.isEmpty()) {
            return record(merchantId, type, amount, referenceType, referenceId);
        }
        Money cumulative = existing.get().amount().add(amount);
        return repository.updateAmountAndBalance(existing.get().id(),
                cumulative.amount(), balanceAfter(merchantId, amount).amount());
    }

    private Money balanceAfter(UUID merchantId, Money amount) {
        BigDecimal last = repository.findLatestByMerchantId(merchantId)
                .map(entry -> entry.balanceAfter().amount())
                .orElse(BigDecimal.ZERO);
        return Money.of(last).add(amount);
    }
}
