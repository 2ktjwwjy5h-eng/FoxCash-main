package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Caso de uso: cobranca automatica de faturas no vencimento (Spec 33).
 *
 * <p>Fecha o ciclo de faturamento: fatura {@code PENDING}/{@code OVERDUE} com
 * saldo devedor e cobrada do wallet do lojista (total quando ha saldo, parcial
 * caso contrario com divida formal {@code receivable}); o job tambem quita
 * receivables de estorno com o saldo disponivel. A receita da plataforma e
 * registrada no ledger do master (Spec 34) na mesma transacao.
 */
public interface InvoiceCollectionUseCase {

    /**
     * Cobra uma fatura especifica (manual, master) — idempotente: fatura ja
     * quitada e no-op. {@code actorId}/{@code ip} (opcionais) geram a trilha de
     * auditoria {@code INVOICE_COLLECT} (Spec 37) quando informados.
     *
     * @return debito aplicado e saldo devedor restante
     */
    CollectionResult collect(UUID merchantId, UUID invoiceId, UUID actorId, String ip, Instant now);

    /**
     * Job diario: cobra todas as faturas vencidas e quita receivables de
     * estorno com o saldo disponivel.
     */
    CollectionResult collectDueInvoices(Instant now);

    record CollectionResult(Money debit, Money remaining, int invoicesCollected) {

        /** Cobranca sem efeito (fatura ja quitada / nada a fazer). */
        public static CollectionResult noop() {
            return new CollectionResult(Money.of("0.00"), Money.of("0.00"), 0);
        }
    }
}
