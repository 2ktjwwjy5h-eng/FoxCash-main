package com.foxcash.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.reconciliation.ReconciliationIssue;
import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.in.WalletReconciliationUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.ReconciliationIssueRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da reconciliação do saldo (Spec 30): valida wallet_balance contra o
 * ledger (Spec 27), registra issues OPEN nas divergências (valores esperado/
 * atual) e contabiliza cobranças órfãs (PENDING sem externalReference).
 */
class WalletReconciliationServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final WalletLedgerRepository ledgerRepository = mock(WalletLedgerRepository.class);
    private final ReconciliationIssueRepository issuesRepository = mock(ReconciliationIssueRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

    private WalletReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new WalletReconciliationService(
                merchantRepository, ledgerRepository, issuesRepository, transactionRepository);
        when(issuesRepository.open(any(), any(), any(), any()))
                .thenAnswer(inv -> ReconciliationIssue.open(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
    }

    private Merchant merchant(Money balance) {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, balance, false, Instant.now());
    }

    private WalletLedgerEntry ledgerEntry(Money balanceAfter) {
        return new WalletLedgerEntry(UUID.randomUUID(), MERCHANT_ID, WalletLedgerEntryType.SALE_CREDIT,
                Money.of("150.00"), balanceAfter, "TRANSACTION", UUID.randomUUID(), Instant.now());
    }

    /** Stub da varredura paginada (auditoria 2026-08-05/M-A3): uma única página. */
    private void stubMerchantsPage(List<Merchant> merchants) {
        when(merchantRepository.findAll(anyInt(), anyInt()))
                .thenReturn(PageResult.of(merchants, 0, 200, merchants.size()));
    }

    @Test
    void consistentMerchantProducesNoIssue() {
        stubMerchantsPage(List.of(merchant(Money.of("150.00"))));
        when(ledgerRepository.findLatestByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(ledgerEntry(Money.of("150.00"))));
        when(transactionRepository.countPendingWithoutExternalReference()).thenReturn(0L);

        WalletReconciliationUseCase.ReconciliationReport report = service.run();

        assertThat(report.checked()).isEqualTo(1);
        assertThat(report.ok()).isEqualTo(1);
        assertThat(report.issues()).isZero();
        verify(issuesRepository, never()).open(any(), any(), any(), any());
    }

    @Test
    void merchantWithoutLedgerIsConsistent() {
        // Sem entry de ledger (ex.: lojista sem movimentação): esperado é o próprio
        // saldo — consistente por definição.
        stubMerchantsPage(List.of(merchant(Money.of("0.00"))));
        when(ledgerRepository.findLatestByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        when(transactionRepository.countPendingWithoutExternalReference()).thenReturn(0L);

        WalletReconciliationUseCase.ReconciliationReport report = service.run();

        assertThat(report.ok()).isEqualTo(1);
        assertThat(report.issues()).isZero();
        verify(issuesRepository, never()).open(any(), any(), any(), any());
    }

    @Test
    void divergentMerchantRegistersOpenIssueWithExpectedAndActual() {
        stubMerchantsPage(List.of(merchant(Money.of("150.00"))));
        // Ledger diz 100; wallet_balance diz 150 → divergência.
        when(ledgerRepository.findLatestByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(ledgerEntry(Money.of("100.00"))));
        when(transactionRepository.countPendingWithoutExternalReference()).thenReturn(0L);

        WalletReconciliationUseCase.ReconciliationReport report = service.run();

        assertThat(report.checked()).isEqualTo(1);
        assertThat(report.ok()).isZero();
        assertThat(report.issues()).isEqualTo(1);

        ArgumentCaptor<Money> expectedCaptor = ArgumentCaptor.forClass(Money.class);
        ArgumentCaptor<Money> actualCaptor = ArgumentCaptor.forClass(Money.class);
        verify(issuesRepository).open(eq(MERCHANT_ID), expectedCaptor.capture(), actualCaptor.capture(), any());
        assertThat(expectedCaptor.getValue().amount()).isEqualByComparingTo("100.00");
        assertThat(actualCaptor.getValue().amount()).isEqualByComparingTo("150.00");
    }

    @Test
    void orphanChargesAreCountedInReport() {
        stubMerchantsPage(List.of(merchant(Money.of("0.00"))));
        when(ledgerRepository.findLatestByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        when(transactionRepository.countPendingWithoutExternalReference()).thenReturn(3L);

        WalletReconciliationUseCase.ReconciliationReport report = service.run();

        assertThat(report.orphanCharges()).isEqualTo(3);
    }

    @Test
    void multipleDivergentMerchantsEachGetOneIssue() {
        UUID otherId = UUID.randomUUID();
        Merchant m1 = merchant(Money.of("150.00"));
        Merchant m2 = Merchant.restore(otherId, "Loja B LTDA", null, "11444778000161",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("50.00"), false, Instant.now());
        stubMerchantsPage(List.of(m1, m2));
        when(ledgerRepository.findLatestByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(ledgerEntry(Money.of("100.00"))));
        when(ledgerRepository.findLatestByMerchantId(otherId))
                .thenReturn(Optional.of(ledgerEntry(Money.of("0.00"))));
        when(transactionRepository.countPendingWithoutExternalReference()).thenReturn(0L);

        WalletReconciliationUseCase.ReconciliationReport report = service.run();

        assertThat(report.checked()).isEqualTo(2);
        assertThat(report.issues()).isEqualTo(2);
        verify(issuesRepository).open(eq(MERCHANT_ID), any(), any(), any());
        verify(issuesRepository).open(eq(otherId), any(), any(), any());
    }

    // ---------- round 8 (mutations: L83 paginação, L85 log de divergências) ----------

    @Test
    void processesAllPagesOfMerchants() {
        // Mutation (NegateConditionals L83): `!batch.items().isEmpty()` → `isEmpty()`.
        // Com 2 páginas, a varredura deve processar AMBAS — o mutante pararia
        // após a página 0 (totalElements 250/200 → 2 páginas).
        UUID otherId = UUID.randomUUID();
        Merchant m1 = merchant(Money.of("150.00"));
        Merchant m2 = Merchant.restore(otherId, "Loja B LTDA", null, "11444778000161",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("50.00"), false, Instant.now());
        when(merchantRepository.findAll(eq(0), eq(200)))
                .thenReturn(PageResult.of(List.of(m1), 0, 200, 250));
        when(merchantRepository.findAll(eq(1), eq(200)))
                .thenReturn(PageResult.of(List.of(m2), 1, 200, 250));
        // Sem ledger → esperado é o próprio saldo (consistente por definição).
        when(ledgerRepository.findLatestByMerchantId(any())).thenReturn(Optional.empty());
        when(transactionRepository.countPendingWithoutExternalReference()).thenReturn(0L);

        WalletReconciliationUseCase.ReconciliationReport report = service.run();

        assertThat(report.checked()).isEqualTo(2);
        assertThat(report.ok()).isEqualTo(2);
    }

    @Test
    void silentWhenNoDivergences() {
        // Mutations (ConditionalsBoundary `>= 0` + NegateConditionals `<= 0` L85):
        // com zero divergências o warn de resumo NÃO deve aparecer.
        stubMerchantsPage(List.of(merchant(Money.of("150.00"))));
        when(ledgerRepository.findLatestByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(ledgerEntry(Money.of("150.00"))));
        when(transactionRepository.countPendingWithoutExternalReference()).thenReturn(0L);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            service.run();
            assertThat(logs(appender)).doesNotContain("divergencias");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void divergenceCountInWarnLogIsAccurate() {
        // Mutation (Math L85: checked - ok → checked + ok): 2 checados, 1
        // divergente → o log deve dizer "1 divergencias" (mutante: "3").
        UUID otherId = UUID.randomUUID();
        Merchant divergent = merchant(Money.of("150.00"));
        Merchant consistent = Merchant.restore(otherId, "Loja B LTDA", null, "11444778000161",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("50.00"), false, Instant.now());
        stubMerchantsPage(List.of(divergent, consistent));
        when(ledgerRepository.findLatestByMerchantId(MERCHANT_ID))
                .thenReturn(Optional.of(ledgerEntry(Money.of("100.00"))));
        when(ledgerRepository.findLatestByMerchantId(otherId)).thenReturn(Optional.empty());
        when(transactionRepository.countPendingWithoutExternalReference()).thenReturn(0L);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            service.run();
            assertThat(logs(appender)).contains("1 divergencias em 2 lojistas");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void findIssuesDelegatesToRepository() {
        // Mutation (NullReturnVals L96): findIssues é delegação pura — deve
        // devolver a página do issuesRepository (não null).
        ReconciliationIssue issue = ReconciliationIssue.open(MERCHANT_ID,
                Money.of("100.00"), Money.of("150.00"), Instant.now());
        when(issuesRepository.find(MERCHANT_ID, ReconciliationIssueStatus.OPEN, 0, 10))
                .thenReturn(PageResult.of(List.of(issue), 0, 10, 1));

        PageResult<ReconciliationIssue> result =
                service.findIssues(MERCHANT_ID, ReconciliationIssueStatus.OPEN, 0, 10);

        assertThat(result.items()).hasSize(1);
        verify(issuesRepository).find(MERCHANT_ID, ReconciliationIssueStatus.OPEN, 0, 10);
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(WalletReconciliationService.class);
        logger.setLevel(Level.WARN);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static String logs(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(WalletReconciliationService.class);
        logger.detachAppender(appender);
        logger.setLevel(null);
    }
}
