package com.foxcash.application.service;

import com.foxcash.domain.exception.InvoiceNotFoundException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.in.InvoiceCollectionUseCase;
import com.foxcash.domain.port.out.InvoiceRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.ReceivableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cobranca automatica de faturas no vencimento (Spec 33) — fecha o ciclo de
 * faturamento.
 *
 * <p><b>Cobranca da fatura:</b> {@code collect} debita do wallet o minimo entre
 * o saldo disponivel e o saldo devedor (pagamento total ou parcial), registra
 * {@code INVOICE_FEE_DEBIT} no ledger (Spec 27) na mesma transacao e formaliza
 * a divida restante em {@code receivables} quando o saldo nao cobre a fatura
 * ({@code OVERDUE}). Idempotente: fatura ja quitada e no-op; fatura
 * {@code OVERDUE} com saldo devedor e re-cobrada quando o lojista volta a ter
 * saldo. Quando a fatura e QUITADA, a receita da plataforma e registrada no
 * ledger do master (Spec 34 — {@code TRANSACTION_FEE} + {@code MONTHLY_FEE}).
 * O collect MANUAL (actorId informado) gera trilha de auditoria
 * {@code INVOICE_COLLECT} (Spec 37).
 *
 * <p><b>Divida de estorno:</b> {@code collectDueInvoices} tambem quita
 * receivables {@code CHARGEBACK}/{@code REFUND} com o saldo disponivel (fecha o
 * ciclo da divida formal) e registra {@code RECEIVABLE_RECOVERY} no ledger do
 * master (Spec 34).
 */
@Service
public class InvoiceCollectionService implements InvoiceCollectionUseCase {

    private static final List<ReceivableOrigin> REFUND_ORIGINS =
            List.of(ReceivableOrigin.CHARGEBACK, ReceivableOrigin.REFUND);

    private final InvoiceRepository invoiceRepository;
    private final MerchantRepository merchantRepository;
    private final ReceivableRepository receivableRepository;
    private final WalletLedgerRecorder ledgerRecorder;
    private final MasterLedgerRecorder masterLedgerRecorder;
    private final MasterAuditRecorder auditRecorder;
    private final WebhookEventPublisher webhookEventPublisher;
    private final PlatformActivityRecorder activityRecorder;
    private final MasterPermissionGuard permissionGuard;
    private final MasterStepUpGuard stepUpGuard;

    public InvoiceCollectionService(InvoiceRepository invoiceRepository,
                                    MerchantRepository merchantRepository,
                                    ReceivableRepository receivableRepository,
                                    WalletLedgerRecorder ledgerRecorder,
                                    MasterLedgerRecorder masterLedgerRecorder,
                                    MasterAuditRecorder auditRecorder,
                                    WebhookEventPublisher webhookEventPublisher,
                                    PlatformActivityRecorder activityRecorder,
                                    MasterPermissionGuard permissionGuard,
                                    MasterStepUpGuard stepUpGuard) {
        this.invoiceRepository = invoiceRepository;
        this.merchantRepository = merchantRepository;
        this.receivableRepository = receivableRepository;
        this.ledgerRecorder = ledgerRecorder;
        this.masterLedgerRecorder = masterLedgerRecorder;
        this.auditRecorder = auditRecorder;
        this.webhookEventPublisher = webhookEventPublisher;
        this.activityRecorder = activityRecorder;
        this.permissionGuard = permissionGuard;
        this.stepUpGuard = stepUpGuard;
    }

    @Override
    @Transactional
    public CollectionResult collect(UUID merchantId, UUID invoiceId, UUID actorId, String ip, Instant now) {
        // Spec 69: collect MANUAL é ação sensível — permissão (todo master ativo)
        // + step-up MFA. O job diário (actorId = null) NÃO passa pelos guards.
        if (actorId != null) {
            Master actor = permissionGuard.requirePermission(actorId, MasterPermission.INVOICE_COLLECT);
            stepUpGuard.requireSteppedUp(actor, MasterPermission.INVOICE_COLLECT);
        }
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (!invoice.merchantId().equals(merchantId)) {
            // 404 — nao revela a existencia de fatura de outro lojista.
            throw new InvoiceNotFoundException(invoiceId);
        }
        Map<String, Object> before = Map.of(
                "status", invoice.status().name(),
                "paidAmount", invoice.paidAmount().amount());
        CollectionResult result = collect(invoice, now);
        // Auditoria (Spec 37): apenas o collect MANUAL (actorId do principal);
        // o job diario nao e acao administrativa do master.
        if (actorId != null) {
            auditRecorder.record(actorId, MasterAuditAction.INVOICE_COLLECT,
                    merchantId, "INVOICE", invoiceId, before,
                    Map.of("status", invoice.status().name(),
                            "paidAmount", invoice.paidAmount().amount(),
                            "debit", result.debit().amount()),
                    null, ip);
        }
        // Spec 41: relatório de atividades — fatura cobrada (manual = MASTER, job = SYSTEM).
        activityRecorder.record(actorId != null ? PlatformActorType.MASTER : PlatformActorType.SYSTEM,
                actorId, PlatformAction.INVOICE_COLLECTED, "INVOICE", invoiceId, merchantId,
                Map.of("debit", result.debit().amount()));
        return result;
    }

    private CollectionResult collect(Invoice invoice, Instant now) {
        if (invoice.outstanding().amount().signum() <= 0) {
            return CollectionResult.noop(); // quitada -> idempotente
        }

        // Lock pessimista no lojista: serializa com saques/créditos/estornos.
        Merchant merchant = merchantRepository.findByIdForUpdate(invoice.merchantId())
                .orElseThrow(() -> new MerchantNotFoundException(invoice.merchantId()));

        // SEC-14 (revisão de segurança 2026-08-11): re-leitura FRESCA da fatura
        // APÓS adquirir o lock do merchant. O lock é o ponto único de mutação do
        // saldo/fatura (todos os fluxos de dinheiro travam o merchant), então a
        // snapshot original carregada pelo chamador pode estar stale — ex.: outra
        // coleta concorrente da MESMA fatura (job diário + collect manual do
        // master) já reduziu o outstanding. Sem a re-leitura, o débito seria
        // calculado sobre o valor antigo (lost update na fatura).
        Invoice fresh = invoiceRepository.findById(invoice.id())
                .orElseThrow(() -> new InvoiceNotFoundException(invoice.id()));
        if (fresh.outstanding().amount().signum() <= 0) {
            return CollectionResult.noop(); // outra coleta concorrente quitou
        }

        Money available = merchant.walletBalance();
        Money debit = Money.of(available.amount().min(fresh.outstanding().amount()));
        if (debit.amount().signum() > 0) {
            merchant.debit(debit);
            fresh.applyPayment(debit, now);
            // Ledger (Spec 27): a cobranca da fatura gera UMA entry
            // INVOICE_FEE_DEBIT (referencia = invoice). Como a fatura pode ser
            // cobrada em multiplas etapas (parcial + re-cobranca), a entry e
            // CUMULATIVA (Spec 33): a re-cobranca acumula o total pago.
            ledgerRecorder.recordCumulative(merchant, WalletLedgerEntryType.INVOICE_FEE_DEBIT,
                    debit, "INVOICE", fresh.id());
        }

        if (fresh.isFullyPaid()) {
            fresh.markPaid();
            receivableRepository.markPaidIfOpen(fresh.merchantId(), ReceivableOrigin.INVOICE, fresh.id(), now);
            // Ledger do master (Spec 34): fatura quitada = receita da plataforma.
            // Duas entries com a mesma referencia (invoice.id) — o entry_type
            // faz parte da unique (TRANSACTION_FEE + MONTHLY_FEE).
            masterLedgerRecorder.record(fresh.merchantId(), MasterLedgerEntryType.TRANSACTION_FEE,
                    fresh.transactionFeesTotal(), "INVOICE", fresh.id());
            masterLedgerRecorder.record(fresh.merchantId(), MasterLedgerEntryType.MONTHLY_FEE,
                    fresh.monthlyFee(), "INVOICE", fresh.id());
            // Webhook de saida (Spec 35): fatura quitada na MESMA transacao.
            webhookEventPublisher.publish(fresh.merchantId(), WebhookEventType.INVOICE_COLLECTED,
                    Map.of("invoiceId", fresh.id(), "debit", debit.amount(),
                            "status", fresh.status().name()));
        } else {
            fresh.markOverdue();
            // Divida formal: o que ainda falta pagar (upsert idempotente).
            receivableRepository.upsertOpen(fresh.merchantId(), ReceivableOrigin.INVOICE, fresh.id(),
                    fresh.outstanding(), now);
        }

        merchantRepository.save(merchant);
        invoiceRepository.save(fresh);
        return new CollectionResult(debit, fresh.outstanding(), 1);
    }

    @Override
    @Transactional
    public CollectionResult collectDueInvoices(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        List<Invoice> due = invoiceRepository.findUnpaidDueOnOrBefore(today);
        int collected = 0;
        for (Invoice invoice : due) {
            collect(invoice, now);
            collected++;
        }
        collectReceivables(now);
        return new CollectionResult(Money.of("0.00"), Money.of("0.00"), collected);
    }

    /**
     * Quita a divida de estorno ({@code CHARGEBACK}/{@code REFUND}) com o saldo
     * disponivel do lojista, agrupando por lojista sob o lock pessimista — nunca
     * deixa o saldo negativo. A receita efetiva da plataforma e registrada no
     * ledger do master (Spec 34 — {@code RECEIVABLE_RECOVERY}, cumulativo:
     * receivable pode ser recuperado em multiplas etapas).
     */
    private void collectReceivables(Instant now) {
        List<Receivable> openDebts = receivableRepository.findOpenByOrigins(REFUND_ORIGINS);
        Map<UUID, List<Receivable>> byMerchant = openDebts.stream()
                .collect(Collectors.groupingBy(Receivable::merchantId));

        for (Map.Entry<UUID, List<Receivable>> entry : byMerchant.entrySet()) {
            Merchant merchant = merchantRepository.findByIdForUpdate(entry.getKey())
                    .orElseThrow(() -> new MerchantNotFoundException(entry.getKey()));
            for (Receivable receivable : entry.getValue()) {
                if (merchant.walletBalance().amount().signum() <= 0) {
                    break;
                }
                Money debit = Money.of(merchant.walletBalance().amount().min(receivable.remaining().amount()));
                if (debit.amount().signum() <= 0) {
                    continue;
                }
                merchant.debit(debit);
                receivable.applyPayment(debit, now);
                // Ledger (Spec 27): a recuperacao da divida gera UMA entry
                // RECEIVABLE_DEBIT (referencia = receivable). A divida pode ser
                // recuperada em multiplas etapas — entry CUMULATIVA (Spec 33).
                ledgerRecorder.recordCumulative(merchant, WalletLedgerEntryType.RECEIVABLE_DEBIT,
                        debit, "RECEIVABLE", receivable.id());
                // Ledger do master (Spec 34): RECEIVABLE_RECOVERY cumulativo.
                masterLedgerRecorder.recordCumulative(receivable.merchantId(),
                        MasterLedgerEntryType.RECEIVABLE_RECOVERY,
                        debit, "RECEIVABLE", receivable.id());
                receivableRepository.save(receivable);
            }
            merchantRepository.save(merchant);
        }
    }
}
