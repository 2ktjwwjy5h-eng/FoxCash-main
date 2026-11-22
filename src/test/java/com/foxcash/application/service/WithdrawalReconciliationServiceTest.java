package com.foxcash.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.domain.port.out.WithdrawalRepository;
import com.foxcash.infrastructure.config.PspProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link WithdrawalReconciliationService} (Spec 36): encerra saques
 * PROCESSING orfaos consultando o PSP e usa o MESMO
 * {@link WithdrawalSettlementService} do webhook (gate de status — sem duplicar
 * estorno).
 */
class WithdrawalReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final WithdrawalRepository withdrawalRepository = mock(WithdrawalRepository.class);
    private final PayoutProviderClient payoutProvider = mock(PayoutProviderClient.class);
    private final WithdrawalSettlementService settlement = mock(WithdrawalSettlementService.class);
    private final WithdrawalSubmissionPersister submissionPersister = mock(WithdrawalSubmissionPersister.class);
    private final PspProperties pspProperties = new PspProperties(); // pending-since-hours=2

    private WithdrawalReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new WithdrawalReconciliationService(withdrawalRepository, payoutProvider,
                settlement, submissionPersister, pspProperties);
    }

    private Withdrawal orphan(String externalReference) {
        return Withdrawal.processing(MERCHANT_ID, Money.of("30.00"), Money.of("2.50"), null,
                "PIX_CPF:52998224725", WithdrawalType.MERCHANT, null)
                .submitToPsp(externalReference, "MOCK");
    }

    /** Orfão PROCESSING sem external_reference (falha entre Fase A e B — B-04). */
    private Withdrawal orphanWithoutReference() {
        return Withdrawal.processing(MERCHANT_ID, Money.of("30.00"), Money.of("2.50"), null,
                "PIX_CPF:52998224725", WithdrawalType.MERCHANT, null);
    }

    @Test
    void settlesOrphanWhenProviderSaysPaid() {
        when(withdrawalRepository.findProcessingOlderThan(any(Instant.class)))
                .thenReturn(List.of(orphan("PO1")));
        when(payoutProvider.getPayout("PO1")).thenReturn(PayoutProviderClient.PayoutStatus.PAID);

        int settled = service.reconcile(NOW);

        assertThat(settled).isEqualTo(1);
        verify(settlement).settleByExternalReference("PO1", "PAID", null);
    }

    @Test
    void failsOrphanWhenProviderSaysFailed() {
        when(withdrawalRepository.findProcessingOlderThan(any(Instant.class)))
                .thenReturn(List.of(orphan("PO1")));
        when(payoutProvider.getPayout("PO1")).thenReturn(PayoutProviderClient.PayoutStatus.FAILED);

        int settled = service.reconcile(NOW);

        assertThat(settled).isEqualTo(1);
        verify(settlement).settleByExternalReference("PO1", "FAILED",
                "reconciliacao: falha no PSP");
    }

    @Test
    void pendingOrphanIsSkippedUntilNextRun() {
        // Spec 38: adquirente real com estado intermediario (PENDING) — a
        // reconciliacao NAO liquida (nao estorna o debito indevidamente); o
        // saque permanece PROCESSING e o proximo run re-consulta.
        when(withdrawalRepository.findProcessingOlderThan(any(Instant.class)))
                .thenReturn(List.of(orphan("PO1")));
        when(payoutProvider.getPayout("PO1")).thenReturn(PayoutProviderClient.PayoutStatus.PENDING);

        int settled = service.reconcile(NOW);

        assertThat(settled).isZero();
        verify(settlement, never()).settleByExternalReference(any(), any(), any());
        // Ainda PROCESSING (não foi liquidado) — o próximo run re-consulta.
        verify(withdrawalRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenNoOrphans() {
        when(withdrawalRepository.findProcessingOlderThan(any(Instant.class)))
                .thenReturn(List.of());

        int settled = service.reconcile(NOW);

        assertThat(settled).isZero();
        verify(payoutProvider, never()).getPayout(any());
        verify(settlement, never()).settleByExternalReference(any(), any(), any());
    }

    @Test
    void reExecutionDoesNotDuplicate() {
        // Re-execucao apos liquidar: o orfao nao volta (nao esta mais PROCESSING),
        // entao findProcessingOlderThan nao o retorna de novo.
        when(withdrawalRepository.findProcessingOlderThan(any(Instant.class)))
                .thenReturn(List.of());

        assertThat(service.reconcile(NOW)).isZero();
        assertThat(service.reconcile(NOW)).isZero();
        verify(settlement, never()).settleByExternalReference(any(), any(), any());
    }

    // ---------- B-04 (auditoria 2026-08-05): PROCESSING sem external_reference ----------

    @Test
    void orphanWithoutReferenceIsRetriedWithSameWithdrawalId() {
        // Falha entre o commit da Fase A e o registro da referência: a reconciliação
        // re-chama o PSP com o MESMO withdrawal.id (idempotente no adquirente) e
        // grava a referência — sem duplo débito nem saque fantasma.
        Withdrawal orphan = orphanWithoutReference();
        when(withdrawalRepository.findProcessingOlderThan(any(Instant.class))).thenReturn(List.of(orphan));
        when(payoutProvider.createPayout(any())).thenReturn(new PayoutProviderClient.PayoutResult("PO1"));
        Withdrawal submitted = orphan.submitToPsp("PO1", "MOCK");
        when(submissionPersister.submit(orphan.id(), "PO1", "MOCK")).thenReturn(submitted);

        int settled = service.reconcile(NOW);

        assertThat(settled).isEqualTo(1);
        // getPayout NÃO é chamado para orfão sem referência (evita NPE).
        verify(payoutProvider, never()).getPayout(any());
        // PSP chamado com o MESMO id do saque.
        verify(payoutProvider).createPayout(any());
        verify(submissionPersister).submit(orphan.id(), "PO1", "MOCK");
    }

    @Test
    void orphanWithoutReferenceStaysProcessingWhenRetryFails() {
        Withdrawal orphan = orphanWithoutReference();
        when(withdrawalRepository.findProcessingOlderThan(any(Instant.class))).thenReturn(List.of(orphan));
        when(payoutProvider.createPayout(any()))
                .thenThrow(new IllegalStateException("PSP indisponivel"));

        int settled = service.reconcile(NOW);

        // Retry falhou → permanece PROCESSING para o próximo run (sem estorno).
        assertThat(settled).isZero();
        verify(submissionPersister, never()).submit(any(), any(), any());
        verify(settlement, never()).settleByExternalReference(any(), any(), any());
    }

    // ---------- reconstrução do destino a partir do snapshot (B-04) ----------

    private PayoutProviderClient.PayoutCommand capturedPayoutCommand(String snapshot) {
        clearInvocations(payoutProvider, withdrawalRepository, submissionPersister);
        Withdrawal orphan = Withdrawal.processing(MERCHANT_ID, Money.of("30.00"), Money.of("2.50"), null,
                snapshot, WithdrawalType.MERCHANT, null);
        when(withdrawalRepository.findProcessingOlderThan(any(Instant.class))).thenReturn(List.of(orphan));
        when(payoutProvider.createPayout(any())).thenReturn(new PayoutProviderClient.PayoutResult("PO1"));

        assertThat(service.reconcile(NOW)).isEqualTo(1);

        ArgumentCaptor<PayoutProviderClient.PayoutCommand> captor =
                ArgumentCaptor.forClass(PayoutProviderClient.PayoutCommand.class);
        verify(payoutProvider).createPayout(captor.capture());
        return captor.getValue();
    }

    @Test
    void retryReconstructsPixKeyDestinationFromSnapshot() {
        // "TYPE:chave" sem ':' dentro da chave → pixKey é a parte após o ':'.
        PayoutProviderClient.PayoutDestination destination =
                capturedPayoutCommand("PIX_CPF:52998224725").destination();

        assertThat(destination.pixKey()).isEqualTo("52998224725");
        assertThat(destination.bankCode()).isNull();
        assertThat(destination.agency()).isNull();
        assertThat(destination.account()).isNull();
        assertThat(destination.accountType()).isNull();
    }

    @Test
    void retryReconstructsBankAccountDestinationFromSnapshot() {
        PayoutProviderClient.PayoutDestination destination =
                capturedPayoutCommand("BANK_ACCOUNT:341|0001|12345-9|cc").destination();

        assertThat(destination.pixKey()).isNull();
        assertThat(destination.bankCode()).isEqualTo("341");
        assertThat(destination.agency()).isEqualTo("0001");
        assertThat(destination.account()).isEqualTo("12345-9");
        assertThat(destination.accountType()).isEqualTo("cc");
    }

    @Test
    void retryWithNullSnapshotPassesNullDestination() {
        PayoutProviderClient.PayoutDestination destination =
                capturedPayoutCommand(null).destination();

        assertThat(destination.pixKey()).isNull();
        assertThat(destination.bankCode()).isNull();
        assertThat(destination.agency()).isNull();
        assertThat(destination.account()).isNull();
        assertThat(destination.accountType()).isNull();
    }

    @Test
    void retryWithSnapshotStartingWithColonTreatsWholeAsKey() {
        // ':' na posição 0 → sem tipo: a chave bruta é o snapshot inteiro.
        PayoutProviderClient.PayoutDestination destination =
                capturedPayoutCommand(":52998224725").destination();

        assertThat(destination.pixKey()).isEqualTo(":52998224725");
        assertThat(destination.bankCode()).isNull();
    }

    @Test
    void retryWithBankSnapshotMissingPartsHandlesNullFields() {
        // 1 parte (só banco).
        PayoutProviderClient.PayoutDestination one =
                capturedPayoutCommand("BANK_ACCOUNT:341").destination();
        assertThat(one.bankCode()).isEqualTo("341");
        assertThat(one.agency()).isNull();
        assertThat(one.account()).isNull();
        assertThat(one.accountType()).isNull();

        // 2 partes (banco+agência, sem conta) — borda do parts.length > 2.
        PayoutProviderClient.PayoutDestination two =
                capturedPayoutCommand("BANK_ACCOUNT:341|0001").destination();
        assertThat(two.bankCode()).isEqualTo("341");
        assertThat(two.agency()).isEqualTo("0001");
        assertThat(two.account()).isNull();
        assertThat(two.accountType()).isNull();

        // 3 partes (sem tipo de conta) — borda do parts.length > 3.
        PayoutProviderClient.PayoutDestination three =
                capturedPayoutCommand("BANK_ACCOUNT:341|0001|12345-9").destination();
        assertThat(three.bankCode()).isEqualTo("341");
        assertThat(three.agency()).isEqualTo("0001");
        assertThat(three.account()).isEqualTo("12345-9");
        assertThat(three.accountType()).isNull();
    }

    // ---------- log de resumo (mutation: ConditionalsBoundary/NegateConditionals em settled > 0) ----------

    @Test
    void logsWhenSomethingSettled() {
        // settled > 0 → o log de resumo DEVE aparecer (mata NegateConditionals L88).
        when(withdrawalRepository.findProcessingOlderThan(any(Instant.class)))
                .thenReturn(List.of(orphan("PO1")));
        when(payoutProvider.getPayout("PO1")).thenReturn(PayoutProviderClient.PayoutStatus.PAID);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            assertThat(service.reconcile(NOW)).isEqualTo(1);
            assertThat(logs(appender)).contains("1 PROCESSING orfaos liquidados");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void silentWhenNothingSettled() {
        // settled == 0 → o log de resumo NÃO deve aparecer (mata ConditionalsBoundary
        // L88: mutante `>= 0` logaria com zero).
        when(withdrawalRepository.findProcessingOlderThan(any(Instant.class)))
                .thenReturn(List.of());

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            assertThat(service.reconcile(NOW)).isZero();
            assertThat(logs(appender)).doesNotContain("orfaos liquidados");
        } finally {
            detachAppender(appender);
        }
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(WithdrawalReconciliationService.class);
        logger.setLevel(Level.INFO);
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
        Logger logger = (Logger) LoggerFactory.getLogger(WithdrawalReconciliationService.class);
        logger.detachAppender(appender);
        logger.setLevel(null);
    }
}
