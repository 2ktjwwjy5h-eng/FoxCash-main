package com.foxcash.application.service;

import com.foxcash.domain.exception.InvoiceNotFoundException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.Invoice;
import com.foxcash.domain.model.billing.InvoiceStatus;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.billing.ReceivableStatus;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.in.InvoiceCollectionUseCase;
import com.foxcash.domain.port.out.InvoiceRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.ReceivableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da cobranca de faturas (Spec 33): total/parcial, idempotencia,
 * re-cobranca de OVERDUE quando o saldo volta e quitação de receivables de
 * estorno com o saldo disponivel — tudo com ledger (Spec 27) na mesma transação.
 */
class InvoiceCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T02:30:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final ReceivableRepository receivableRepository = mock(ReceivableRepository.class);
    private final WalletLedgerRecorder ledgerRecorder = mock(WalletLedgerRecorder.class);
    private final MasterLedgerRecorder masterLedgerRecorder = mock(MasterLedgerRecorder.class);
    private final MasterAuditRecorder auditRecorder = mock(MasterAuditRecorder.class);
    private final WebhookEventPublisher webhookEventPublisher = mock(WebhookEventPublisher.class);
    private final MasterPermissionGuard permissionGuard = mock(MasterPermissionGuard.class);
    private final MasterStepUpGuard stepUpGuard = mock(MasterStepUpGuard.class);

    private InvoiceCollectionService service;
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);

    @BeforeEach
    void setUp() {
        service = new InvoiceCollectionService(invoiceRepository, merchantRepository,
                receivableRepository, ledgerRecorder, masterLedgerRecorder, auditRecorder,
                webhookEventPublisher, activityRecorder, permissionGuard, stepUpGuard);
    }

    private Merchant merchant(String balance) {
        return Merchant.restore(MERCHANT_ID, "Loja Teste LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of(balance), false, NOW);
    }

    private Invoice invoice(InvoiceStatus status, String total, String paid) {
        return Invoice.restore(UUID.randomUUID(), MERCHANT_ID, BillingPeriod.of(YearMonth.of(2026, 7)),
                10, Money.of("5.00"), Money.of("95.00"), Money.of(total),
                LocalDate.of(2026, 8, 5), status, NOW, Money.of(paid), null);
    }

    @Test
    void collectWithSufficientBalanceDebitsTotalMarksPaidAndRecordsLedger() {
        Invoice inv = invoice(InvoiceStatus.PENDING, "100.00", "0.00");
        when(invoiceRepository.findById(inv.id())).thenReturn(Optional.of(inv));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant("200.00")));

        InvoiceCollectionUseCase.CollectionResult result = service.collect(MERCHANT_ID, inv.id(), null, null, NOW);

        assertThat(result.debit().amount()).isEqualByComparingTo("100.00");
        assertThat(result.remaining().amount()).isEqualByComparingTo("0.00");
        assertThat(inv.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(inv.paidAmount().amount()).isEqualByComparingTo("100.00");
        assertThat(inv.collectedAt()).isEqualTo(NOW);

        // L126: o merchant SAVEADO deve ter o saldo debitado (200 - 100 = 100).
        ArgumentCaptor<Merchant> savedMerchant = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(savedMerchant.capture());
        assertThat(savedMerchant.getValue().walletBalance().amount()).isEqualByComparingTo("100.00");
        verify(invoiceRepository).save(inv);
        verify(ledgerRecorder).recordCumulative(any(), eq(WalletLedgerEntryType.INVOICE_FEE_DEBIT),
                eq(Money.of("100.00")), eq("INVOICE"), eq(inv.id()));
        verify(receivableRepository).markPaidIfOpen(MERCHANT_ID, ReceivableOrigin.INVOICE, inv.id(), NOW);
        verify(receivableRepository, never()).upsertOpen(any(), any(), any(), any(), any());
        // L147: fatura quitada → webhook INVOICE_COLLECTED publicado na mesma transação.
        verify(webhookEventPublisher).publish(eq(MERCHANT_ID), eq(WebhookEventType.INVOICE_COLLECTED), any());
    }

    @Test
    void collectWithShortBalanceDebitsAvailableMarksOverdueAndUpsertsReceivable() {
        Invoice inv = invoice(InvoiceStatus.PENDING, "100.00", "0.00");
        when(invoiceRepository.findById(inv.id())).thenReturn(Optional.of(inv));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant("40.00")));

        InvoiceCollectionUseCase.CollectionResult result = service.collect(MERCHANT_ID, inv.id(), null, null, NOW);

        assertThat(result.debit().amount()).isEqualByComparingTo("40.00");
        assertThat(result.remaining().amount()).isEqualByComparingTo("60.00");
        assertThat(inv.status()).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(inv.paidAmount().amount()).isEqualByComparingTo("40.00");

        verify(ledgerRecorder).recordCumulative(any(), eq(WalletLedgerEntryType.INVOICE_FEE_DEBIT),
                eq(Money.of("40.00")), eq("INVOICE"), eq(inv.id()));
        verify(receivableRepository).upsertOpen(MERCHANT_ID, ReceivableOrigin.INVOICE, inv.id(),
                Money.of("60.00"), NOW);
        verify(receivableRepository, never()).markPaidIfOpen(any(), any(), any(), any());
    }

    @Test
    void collectWithZeroBalanceDoesNotDebitButStillMarksOverdueAndUpsertsReceivable() {
        Invoice inv = invoice(InvoiceStatus.PENDING, "100.00", "0.00");
        when(invoiceRepository.findById(inv.id())).thenReturn(Optional.of(inv));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant("0.00")));

        InvoiceCollectionUseCase.CollectionResult result = service.collect(MERCHANT_ID, inv.id(), null, null, NOW);

        assertThat(result.debit().amount()).isEqualByComparingTo("0.00");
        assertThat(result.remaining().amount()).isEqualByComparingTo("100.00");
        assertThat(inv.status()).isEqualTo(InvoiceStatus.OVERDUE);
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
        // L125: saldo zero → bloco de débito NÃO pode rodar (senão recordCumulative(0) seria chamado).
        verify(ledgerRecorder, never()).recordCumulative(any(), any(), any(), any(), any());
        verify(receivableRepository).upsertOpen(MERCHANT_ID, ReceivableOrigin.INVOICE, inv.id(),
                Money.of("100.00"), NOW);
    }

    @Test
    void collectAlreadyPaidInvoiceIsNoop() {
        Invoice inv = invoice(InvoiceStatus.PAID, "100.00", "100.00");
        when(invoiceRepository.findById(inv.id())).thenReturn(Optional.of(inv));

        InvoiceCollectionUseCase.CollectionResult result = service.collect(MERCHANT_ID, inv.id(), null, null, NOW);

        assertThat(result.debit().amount()).isEqualByComparingTo("0.00");
        assertThat(result.remaining().amount()).isEqualByComparingTo("0.00");
        verify(merchantRepository, never()).findByIdForUpdate(any());
        verify(invoiceRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void collectOverdueInvoiceAgainWhenBalanceReturnsQuitsIt() {
        // Fatura OVERDUE com pagamento parcial anterior (40) e saldo que voltou.
        Invoice inv = invoice(InvoiceStatus.OVERDUE, "100.00", "40.00");
        when(invoiceRepository.findById(inv.id())).thenReturn(Optional.of(inv));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant("200.00")));

        InvoiceCollectionUseCase.CollectionResult result = service.collect(MERCHANT_ID, inv.id(), null, null, NOW);

        assertThat(result.debit().amount()).isEqualByComparingTo("60.00");
        assertThat(result.remaining().amount()).isEqualByComparingTo("0.00");
        assertThat(inv.status()).isEqualTo(InvoiceStatus.PAID);
        verify(receivableRepository).markPaidIfOpen(MERCHANT_ID, ReceivableOrigin.INVOICE, inv.id(), NOW);
        verify(ledgerRecorder).recordCumulative(any(), eq(WalletLedgerEntryType.INVOICE_FEE_DEBIT),
                eq(Money.of("60.00")), eq("INVOICE"), eq(inv.id()));
    }

    @Test
    void collectInvoiceOfAnotherMerchantThrowsNotFound() {
        Invoice inv = invoice(InvoiceStatus.PENDING, "100.00", "0.00");
        when(invoiceRepository.findById(inv.id())).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.collect(UUID.randomUUID(), inv.id(), null, null, NOW))
                .isInstanceOf(InvoiceNotFoundException.class);
        verify(merchantRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void collectUnknownInvoiceThrowsNotFound() {
        // Mutation (NullReturnVals L88, lambda do orElseThrow do collect): fatura
        // inexistente → InvoiceNotFoundException (não NPE do `throw null`).
        when(invoiceRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.collect(MERCHANT_ID, UUID.randomUUID(), null, null, NOW))
                .isInstanceOf(InvoiceNotFoundException.class);
        verify(merchantRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void collectWithMissingMerchantThrowsNotFound() {
        // Mutation (NullReturnVals L121, lambda do orElseThrow do collect): lojista
        // inexistente na cobrança da fatura → MerchantNotFoundException.
        Invoice inv = invoice(InvoiceStatus.PENDING, "100.00", "0.00");
        when(invoiceRepository.findById(inv.id())).thenReturn(Optional.of(inv));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.collect(MERCHANT_ID, inv.id(), null, null, NOW))
                .isInstanceOf(MerchantNotFoundException.class);
        verify(merchantRepository, never()).save(any());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void collectDueInvoicesQuitsEstornoReceivableWithAvailableBalance() {
        Invoice due = invoice(InvoiceStatus.PENDING, "100.00", "0.00");
        when(invoiceRepository.findUnpaidDueOnOrBefore(LocalDate.of(2026, 8, 5)))
                .thenReturn(List.of(due));
        // SEC-14: o collect re-lê a fatura com lock do merchant — stub necessário.
        when(invoiceRepository.findById(due.id())).thenReturn(Optional.of(due));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant("200.00")));

        Receivable debt = Receivable.restore(UUID.randomUUID(), MERCHANT_ID, ReceivableOrigin.CHARGEBACK,
                UUID.randomUUID(), Money.of("100.00"), Money.of("0.00"), ReceivableStatus.OPEN,
                NOW, null, NOW);
        when(receivableRepository.findOpenByOrigins(List.of(ReceivableOrigin.CHARGEBACK, ReceivableOrigin.REFUND)))
                .thenReturn(List.of(debt));

        InvoiceCollectionUseCase.CollectionResult result = service.collectDueInvoices(NOW);

        assertThat(result.invoicesCollected()).isEqualTo(1);
        // Divida de estorno quitada na mesma transação do job.
        assertThat(debt.status()).isEqualTo(ReceivableStatus.PAID);
        verify(receivableRepository).save(debt);
        verify(ledgerRecorder).recordCumulative(any(), eq(WalletLedgerEntryType.RECEIVABLE_DEBIT),
                eq(Money.of("100.00")), eq("RECEIVABLE"), eq(debt.id()));
    }

    @Test
    void collectDueInvoicesNeverLeavesNegativeBalanceWhenReceivableExceedsBalance() {
        when(invoiceRepository.findUnpaidDueOnOrBefore(LocalDate.of(2026, 8, 5))).thenReturn(List.of());
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant("40.00")));

        Receivable debt = Receivable.restore(UUID.randomUUID(), MERCHANT_ID, ReceivableOrigin.REFUND,
                UUID.randomUUID(), Money.of("100.00"), Money.of("0.00"), ReceivableStatus.OPEN,
                NOW, null, NOW);
        when(receivableRepository.findOpenByOrigins(any())).thenReturn(List.of(debt));

        service.collectDueInvoices(NOW);

        assertThat(debt.status()).isEqualTo(ReceivableStatus.PARTIAL);
        assertThat(debt.paidAmount().amount()).isEqualByComparingTo("40.00");
        // O merchant foi debitado apenas do disponivel — saldo nunca negativo.
        ArgumentCaptor<Merchant> merchantCaptor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(merchantCaptor.capture());
        assertThat(merchantCaptor.getValue().walletBalance().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void collectDueInvoicesUnknownMerchantThrowsNotFound() {
        // Job tenta quitar receivable de estorno de lojista inexistente -> 404.
        when(invoiceRepository.findUnpaidDueOnOrBefore(any())).thenReturn(List.of());
        Receivable debt = Receivable.restore(UUID.randomUUID(), MERCHANT_ID, ReceivableOrigin.CHARGEBACK,
                UUID.randomUUID(), Money.of("100.00"), Money.of("0.00"), ReceivableStatus.OPEN,
                NOW, null, NOW);
        when(receivableRepository.findOpenByOrigins(any())).thenReturn(List.of(debt));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.collectDueInvoices(NOW))
                .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    void manualCollectRecordsAuditAndMasterActivity() {
        // L99/L108: collect MANUAL (actorId != null) → auditoria INVOICE_COLLECT +
        // activity com PlatformActorType.MASTER (o job usaria SYSTEM e sem auditoria).
        UUID actorId = UUID.randomUUID();
        Invoice inv = invoice(InvoiceStatus.PENDING, "100.00", "0.00");
        when(invoiceRepository.findById(inv.id())).thenReturn(Optional.of(inv));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant("200.00")));

        service.collect(MERCHANT_ID, inv.id(), actorId, "10.0.0.1", NOW);

        verify(auditRecorder).record(eq(actorId), eq(MasterAuditAction.INVOICE_COLLECT), eq(MERCHANT_ID),
                eq("INVOICE"), eq(inv.id()), any(), any(), isNull(), eq("10.0.0.1"));
        verify(activityRecorder).record(eq(PlatformActorType.MASTER), eq(actorId),
                eq(PlatformAction.INVOICE_COLLECTED), eq("INVOICE"), eq(inv.id()), eq(MERCHANT_ID), any());
    }

    @Test
    void collectDueInvoicesSkipsReceivableWithNoRemainingDebt() {
        // L196: receivable com saldo devedor ZERO (min(saldo, 0) = 0) → continue,
        // sem gerar debit/ledger/save.
        when(invoiceRepository.findUnpaidDueOnOrBefore(any())).thenReturn(List.of());
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant("100.00")));
        Receivable paid = Receivable.restore(UUID.randomUUID(), MERCHANT_ID, ReceivableOrigin.REFUND,
                UUID.randomUUID(), Money.of("100.00"), Money.of("100.00"), ReceivableStatus.OPEN,
                NOW, null, NOW);
        when(receivableRepository.findOpenByOrigins(any())).thenReturn(List.of(paid));

        service.collectDueInvoices(NOW);

        assertThat(paid.status()).isEqualTo(ReceivableStatus.OPEN);
        verify(receivableRepository, never()).save(any());
        verify(ledgerRecorder, never()).recordCumulative(any(), any(), any(), any(), any());
    }

    // ---- Spec 69 (critérios 3/8): collect manual é SENSÍVEL (permissão + step-up) ----

    @Test
    void manualCollectRequiresPermissionAndStepUp() {
        // Collect MANUAL (actorId != null): permissão + step-up MFA; step-up
        // negado → 403 step_up_required, sem nenhum efeito (nem o débito).
        com.foxcash.domain.model.master.Master actor = com.foxcash.domain.model.master.Master.restore(
                UUID.randomUUID(), "master", "hash", "Master", true, false, NOW);
        when(permissionGuard.requirePermission(actor.id(),
                com.foxcash.domain.model.master.MasterPermission.INVOICE_COLLECT))
                .thenReturn(actor);
        org.mockito.Mockito.doThrow(new com.foxcash.domain.exception.MasterStepUpRequiredException())
                .when(stepUpGuard).requireSteppedUp(actor,
                        com.foxcash.domain.model.master.MasterPermission.INVOICE_COLLECT);

        Invoice inv = invoice(InvoiceStatus.PENDING, "100.00", "0.00");
        when(invoiceRepository.findById(inv.id())).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.collect(MERCHANT_ID, inv.id(), actor.id(), "10.0.0.1", NOW))
                .isInstanceOf(com.foxcash.domain.exception.MasterStepUpRequiredException.class);

        verify(merchantRepository, never()).findByIdForUpdate(any());
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void jobCollectSkipsPermissionAndStepUpGuards() {
        // O job diário (actorId = null) NÃO passa pelos guards (não é ação
        // administrativa do master) — o fluxo automático continua funcionando.
        Invoice inv = invoice(InvoiceStatus.PENDING, "100.00", "0.00");
        when(invoiceRepository.findById(inv.id())).thenReturn(Optional.of(inv));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant("200.00")));

        service.collect(MERCHANT_ID, inv.id(), null, null, NOW);

        verify(permissionGuard, never()).requirePermission(any(), any(com.foxcash.domain.model.master.MasterPermission.class));
        verify(stepUpGuard, never()).requireSteppedUp(any(), any());
        verify(activityRecorder).record(eq(PlatformActorType.SYSTEM), isNull(),
                eq(PlatformAction.INVOICE_COLLECTED), eq("INVOICE"), eq(inv.id()), eq(MERCHANT_ID), any());
    }

    // ---------- SEC-14 (revisão de segurança 2026-08-11): re-leitura fresca da fatura ----------

    @Test
    void concurrentCollectionThatAlreadyQuitIsNoop() {
        // SEC-14: a fatura é re-lida APÓS o lock do merchant — uma coleta
        // concorrente pode ter quitado (outstanding 0). Boundary L146
        // (`<= 0` → `< 0`): com outstanding EXATAMENTE 0 a coleta é noop — NÃO
        // pode re-registrar receita/ledger/webhook da fatura já quitada.
        Invoice pending = invoice(InvoiceStatus.PENDING, "100.00", "0.00");
        Invoice freshPaid = invoice(InvoiceStatus.PAID, "100.00", "100.00");
        when(invoiceRepository.findById(pending.id())).thenReturn(Optional.of(pending), Optional.of(freshPaid));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant("200.00")));

        InvoiceCollectionUseCase.CollectionResult result =
                service.collect(MERCHANT_ID, pending.id(), null, null, NOW);

        assertThat(result.debit().amount()).isEqualByComparingTo("0.00");
        assertThat(result.remaining().amount()).isEqualByComparingTo("0.00");
        verify(invoiceRepository, never()).save(any());
        verify(ledgerRecorder, never()).recordCumulative(any(), any(), any(), any(), any());
        verify(masterLedgerRecorder, never()).record(any(), any(), any(), any(), any());
        verify(webhookEventPublisher, never()).publish(any(), any(), any());
    }
}
