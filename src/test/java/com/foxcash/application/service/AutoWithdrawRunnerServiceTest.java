package com.foxcash.application.service;

import com.foxcash.domain.exception.AutoWithdrawBelowThresholdException;
import com.foxcash.domain.exception.InsufficientBalanceException;
import com.foxcash.domain.exception.MerchantNotActiveException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.OutstandingDebtException;
import com.foxcash.domain.exception.WithdrawalsBlockedException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.withdrawal.AutoWithdrawConfig;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.port.out.AutoWithdrawConfigRepository;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.domain.port.out.WithdrawalDestinationRepository;
import com.foxcash.infrastructure.config.AutoWithdrawProperties;
import com.foxcash.infrastructure.config.BillingProperties;
import com.foxcash.infrastructure.config.PricingProperties;
import com.foxcash.infrastructure.config.PspProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link AutoWithdrawRunnerService} (Spec 70): kill-switch, skips
 * silenciosos (destino ausente/inativo, nao-ACTIVE, bloqueado, divida, abaixo do
 * limiar no lock, lojista sumiu), sucesso com {@code amount == saldo}, falha
 * sincrona do PSP (estorno + rethrow) e o teto {@code max-withdrawals-per-run}
 * (conta apenas saques criados; skips nao contam).
 */
class AutoWithdrawRunnerServiceTest {

    private final AutoWithdrawConfigRepository configRepository = mock(AutoWithdrawConfigRepository.class);
    private final WithdrawalDestinationRepository destinationRepository = mock(WithdrawalDestinationRepository.class);
    private final WithdrawalProcessingPersister processingPersister = mock(WithdrawalProcessingPersister.class);
    private final BillingProperties billingProperties = new BillingProperties();
    private final PricingProperties pricingProperties = new PricingProperties();
    private final PayoutProviderClient payoutProvider = mock(PayoutProviderClient.class);
    private final PspProperties pspProperties = mock(PspProperties.class);
    private final WithdrawalSubmissionPersister submissionPersister = mock(WithdrawalSubmissionPersister.class);
    private final WithdrawalSettlementService settlement = mock(WithdrawalSettlementService.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final AutoWithdrawProperties properties = new AutoWithdrawProperties();

    private AutoWithdrawRunnerService runner;

    @BeforeEach
    void setUp() {
        runner = new AutoWithdrawRunnerService(configRepository, destinationRepository,
                processingPersister, billingProperties, pricingProperties, payoutProvider, pspProperties,
                submissionPersister, settlement, activityRecorder, properties);
        when(pspProperties.getPayoutProvider()).thenReturn("mock");
        properties.setMaxWithdrawalsPerRun(100);
    }

    private AutoWithdrawConfig config(UUID merchantId, String threshold) {
        return AutoWithdrawConfig.enable(merchantId, Money.of(threshold), UUID.randomUUID());
    }

    private WithdrawalDestination destination(UUID merchantId, boolean active) {
        WithdrawalDestination d = WithdrawalDestination.create(merchantId,
                WithdrawalDestinationType.PIX_CPF, "52998224725", null, null, null, null,
                "Conta pessoal", Instant.now());
        return active ? d : d.deactivate(Instant.now());
    }

    private void stubSuccess(AutoWithdrawConfig config, WithdrawalDestination destination,
                             String amount, String externalReference) {
        when(destinationRepository.findByIdAndMerchantId(config.destinationId(), config.merchantId()))
                .thenReturn(Optional.of(destination));
        Withdrawal withdrawal = Withdrawal.processing(config.merchantId(), Money.of(amount), Money.of("2.50"),
                destination.id(), destination.snapshotValue(), WithdrawalType.MERCHANT, null);
        WithdrawalProcessingPersister.MerchantWithdrawal pending =
                new WithdrawalProcessingPersister.MerchantWithdrawal(withdrawal, Money.of("0.00"));
        when(processingPersister.createMerchantAutoWithdrawal(config.merchantId(), destination,
                config.threshold(), true, Money.of("2.50"))).thenReturn(pending);
        when(payoutProvider.createPayout(any()))
                .thenReturn(new PayoutProviderClient.PayoutResult(externalReference));
        when(submissionPersister.submit(withdrawal.id(), externalReference, "MOCK"))
                .thenReturn(withdrawal);
    }

    // ---- kill-switch ----

    @Test
    void runAllWithKillSwitchDisabledDoesNothing() {
        properties.setEnabled(false);

        AutoWithdrawRunnerService.RunReport report = runner.runAll(Instant.now());

        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isZero();
        verify(configRepository, never()).findEnabled();
    }

    // ---- skips silenciosos ----

    @Test
    void runAllSkipsConfigWithMissingDestination() {
        AutoWithdrawConfig config = config(UUID.randomUUID(), "1000.00");
        when(configRepository.findEnabled()).thenReturn(List.of(config));
        when(destinationRepository.findByIdAndMerchantId(config.destinationId(), config.merchantId()))
                .thenReturn(Optional.empty());

        AutoWithdrawRunnerService.RunReport report = runner.runAll(Instant.now());

        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
        verify(processingPersister, never()).createMerchantAutoWithdrawal(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void runAllSkipsConfigWithInactiveDestination() {
        AutoWithdrawConfig config = config(UUID.randomUUID(), "1000.00");
        when(configRepository.findEnabled()).thenReturn(List.of(config));
        when(destinationRepository.findByIdAndMerchantId(config.destinationId(), config.merchantId()))
                .thenReturn(Optional.of(destination(config.merchantId(), false)));

        AutoWithdrawRunnerService.RunReport report = runner.runAll(Instant.now());

        assertThat(report.created()).isZero();
        assertThat(report.skipped()).isEqualTo(1);
    }

    @Test
    void runForConfigSkipsDomainBlockingConditionsSilently() {
        AutoWithdrawConfig config = config(UUID.randomUUID(), "1000.00");
        WithdrawalDestination destination = destination(config.merchantId(), true);
        when(destinationRepository.findByIdAndMerchantId(config.destinationId(), config.merchantId()))
                .thenReturn(Optional.of(destination));

        // Cada condicao bloqueante → skip silencioso (sem excecao propagada).
        when(processingPersister.createMerchantAutoWithdrawal(config.merchantId(), destination,
                config.threshold(), true, Money.of("2.50")))
                .thenThrow(new MerchantNotActiveException(config.merchantId()))
                .thenThrow(new WithdrawalsBlockedException(config.merchantId()))
                .thenThrow(new OutstandingDebtException(config.merchantId()))
                .thenThrow(new AutoWithdrawBelowThresholdException(config.merchantId(),
                        Money.of("100.00"), config.threshold()))
                .thenThrow(new InsufficientBalanceException(config.merchantId(), config.threshold()))
                .thenThrow(new MerchantNotFoundException(config.merchantId()));

        for (int i = 0; i < 6; i++) {
            assertThat(runner.runForConfig(config)).isFalse();
        }
        verify(payoutProvider, never()).createPayout(any());
    }

    // ---- sucesso ----

    @Test
    void runForConfigSuccessCreatesWithdrawalsAmountEqualsBalanceAndRecordsActivity() {
        AutoWithdrawConfig config = config(UUID.randomUUID(), "1000.00");
        WithdrawalDestination destination = destination(config.merchantId(), true);
        stubSuccess(config, destination, "1234.56", "ref-1");

        assertThat(runner.runForConfig(config)).isTrue();

        verify(submissionPersister).submit(any(), eq("ref-1"), eq("MOCK"));
        verify(activityRecorder).record(eq(PlatformActorType.SYSTEM), eq(null),
                eq(PlatformAction.AUTO_WITHDRAW_REQUESTED), eq("WITHDRAWAL"),
                any(), eq(config.merchantId()),
                eq(Map.of("amount", Money.of("1234.56").amount())));
    }

    // ---- falha sincrona do PSP ----

    @Test
    void runForConfigPspSyncFailureSettlesAndRethrows() {
        AutoWithdrawConfig config = config(UUID.randomUUID(), "1000.00");
        WithdrawalDestination destination = destination(config.merchantId(), true);
        when(destinationRepository.findByIdAndMerchantId(config.destinationId(), config.merchantId()))
                .thenReturn(Optional.of(destination));
        Withdrawal withdrawal = Withdrawal.processing(config.merchantId(), Money.of("1000.00"), Money.of("2.50"),
                destination.id(), destination.snapshotValue(), WithdrawalType.MERCHANT, null);
        when(processingPersister.createMerchantAutoWithdrawal(config.merchantId(), destination,
                config.threshold(), true, Money.of("2.50")))
                .thenReturn(new WithdrawalProcessingPersister.MerchantWithdrawal(withdrawal, Money.of("0.00")));
        when(payoutProvider.createPayout(any()))
                .thenThrow(new IllegalStateException("PSP fora do ar"));

        assertThatThrownBy(() -> runner.runForConfig(config))
                .isInstanceOf(IllegalStateException.class);

        // Estorno em transacao propria + rethrow (JobExecutionService marca FAILED).
        verify(settlement).settleFailedByWithdrawalId(eq(withdrawal.id()),
                org.mockito.ArgumentMatchers.startsWith("Falha no PSP"));
        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    // ---- teto por execucao ----

    @Test
    void runAllStopsAfterMaxWithdrawalsPerRunAndSkipsDoNotCount() {
        properties.setMaxWithdrawalsPerRun(2);
        AutoWithdrawConfig config1 = config(UUID.randomUUID(), "1000.00");
        AutoWithdrawConfig config2 = config(UUID.randomUUID(), "2000.00");
        AutoWithdrawConfig config3 = config(UUID.randomUUID(), "3000.00");
        when(configRepository.findEnabled()).thenReturn(List.of(config1, config2, config3));

        stubSuccess(config1, destination(config1.merchantId(), true), "1500.00", "ref-1");
        stubSuccess(config2, destination(config2.merchantId(), true), "2500.00", "ref-2");
        // config3 não é processado: o teto (2 criados) já foi atingido.
        // O stub de createPayout(any()) da 2ª stubSuccess SOBRESCREVERIA o da 1ª —
        // encadeia os retornos por ordem de chamada (ref-1, ref-2).
        when(payoutProvider.createPayout(any()))
                .thenReturn(new PayoutProviderClient.PayoutResult("ref-1"),
                        new PayoutProviderClient.PayoutResult("ref-2"));

        AutoWithdrawRunnerService.RunReport report = runner.runAll(Instant.now());

        assertThat(report.created()).isEqualTo(2);
        verify(processingPersister, times(2)).createMerchantAutoWithdrawal(any(), any(), any(), anyBoolean(), any());
    }
}
