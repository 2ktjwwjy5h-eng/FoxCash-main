package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.exception.PvsAmountException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.port.in.PvsPaymentUseCase;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.domain.port.out.WithdrawalRepository;
import com.foxcash.domain.pvs.TestPixCodes;
import com.foxcash.infrastructure.config.BillingProperties;
import com.foxcash.infrastructure.config.PricingProperties;
import com.foxcash.infrastructure.config.PspProperties;
import com.foxcash.infrastructure.config.PvsProperties;
import com.foxcash.infrastructure.security.AuthenticatedMerchantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PvsPaymentService} (Spec 120): o orquestrador valida o
 * destino arbitrário pelo parser (única porta), resolve o valor (EMV 54 vs
 * amount), coordena as 2 fases (Fase A sob lock com tetos + Fase B fora da
 * transação) e registra a trilha. Falha síncrona do PSP → estorno de valor +
 * taxa (mesmo fluxo do saque).
 */
class PvsPaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final String PIX_KEY = "52998224725";
    private static final String PIX_CODE = TestPixCodes.staticEmv(PIX_KEY, null);

    private final PricingProperties pricingProperties = new PricingProperties();
    private final PvsProperties pvsProperties = new PvsProperties();
    private final PspProperties pspProperties = new PspProperties();
    private final BillingProperties billingProperties = new BillingProperties();
    private final PayoutProviderClient payoutProvider = mock(PayoutProviderClient.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final WithdrawalProcessingPersister processingPersister = mock(WithdrawalProcessingPersister.class);
    private final WithdrawalSubmissionPersister submissionPersister = mock(WithdrawalSubmissionPersister.class);
    private final WithdrawalSettlementService settlement = mock(WithdrawalSettlementService.class);
    private final WithdrawalRepository withdrawalRepository = mock(WithdrawalRepository.class);
    private final AuthenticatedMerchantResolver authenticatedMerchant = mock(AuthenticatedMerchantResolver.class);

    private PvsPaymentService service;

    @BeforeEach
    void setUp() {
        service = new PvsPaymentService(pricingProperties, pvsProperties, pspProperties,
                billingProperties, payoutProvider, activityRecorder, processingPersister,
                submissionPersister, settlement, withdrawalRepository, authenticatedMerchant);
        when(authenticatedMerchant.currentMerchant()).thenReturn(Optional.empty());
    }

    private Withdrawal pendingWithoutReference() {
        return Withdrawal.processing(MERCHANT_ID, Money.of("40.00"), Money.of("2.50"),
                null, PIX_KEY, WithdrawalType.BILL_PAYMENT, null);
    }

    private PvsPaymentUseCase.PayCommand command(String pixCode, String amount) {
        return new PvsPaymentUseCase.PayCommand(MERCHANT_ID, pixCode,
                amount == null ? null : Money.of(amount));
    }

    private void stubPhaseA() {
        Withdrawal pending = pendingWithoutReference();
        when(processingPersister.createBillPaymentWithdrawal(
                eq(MERCHANT_ID), eq(Money.of("40.00")), eq(Money.of("2.50")), eq(PIX_KEY),
                eq(pvsProperties.getDailyCap()), eq(pvsProperties.getMaxPerPayment()),
                eq(billingProperties.isBlockWithdrawalsOnDebt())))
                .thenReturn(new WithdrawalProcessingPersister.MerchantWithdrawal(pending, Money.of("60.00")));
    }

    @Test
    void paySuccessCoordinatesPhaseAAndPhaseBAndReturnsResult() {
        Withdrawal pending = pendingWithoutReference();
        when(processingPersister.createBillPaymentWithdrawal(
                eq(MERCHANT_ID), eq(Money.of("40.00")), eq(Money.of("2.50")), eq(PIX_KEY),
                eq(pvsProperties.getDailyCap()), eq(pvsProperties.getMaxPerPayment()),
                eq(billingProperties.isBlockWithdrawalsOnDebt())))
                .thenReturn(new WithdrawalProcessingPersister.MerchantWithdrawal(pending, Money.of("60.00")));
        String ref = "PO" + UUID.randomUUID().toString().replace("-", "");
        when(payoutProvider.createPayout(any())).thenReturn(new PayoutProviderClient.PayoutResult(ref));
        Withdrawal submitted = pending.submitToPsp(ref, "MOCK");
        when(submissionPersister.submit(any(), eq(ref), eq("MOCK"))).thenReturn(submitted);

        PvsPaymentUseCase.Result result = service.pay(command(PIX_CODE, "40.00"));

        assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(result.amount().amount()).isEqualByComparingTo("40.00");
        assertThat(result.fee().amount()).isEqualByComparingTo("2.50");
        assertThat(result.walletBalance().amount()).isEqualByComparingTo("60.00");
        assertThat(result.withdrawalId()).isEqualTo(submitted.id());

        // Fase A chamada com o valor efetivo + chave parseada + tetos da config.
        verify(processingPersister).createBillPaymentWithdrawal(
                MERCHANT_ID, Money.of("40.00"), Money.of("2.50"), PIX_KEY,
                pvsProperties.getDailyCap(), pvsProperties.getMaxPerPayment(),
                billingProperties.isBlockWithdrawalsOnDebt());

        // Fase B: PSP chamado com o MESMO withdrawal.id da Fase A (idempotente no
        // adquirente) e o destino montado com a chave do parser (nunca destinationId).
        ArgumentCaptor<PayoutProviderClient.PayoutCommand> cmdCaptor =
                ArgumentCaptor.forClass(PayoutProviderClient.PayoutCommand.class);
        verify(payoutProvider).createPayout(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().withdrawalId()).isEqualTo(pending.id());
        assertThat(cmdCaptor.getValue().destination().pixKey()).isEqualTo(PIX_KEY);
        assertThat(cmdCaptor.getValue().destination().bankCode()).isNull();
        assertThat(cmdCaptor.getValue().amount().amount()).isEqualByComparingTo("40.00");

        verify(submissionPersister).submit(any(), eq(ref), eq("MOCK"));
        // Trilha: valor apenas — a chave nunca vai em claro.
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.BILL_PAYMENT_REQUESTED), eq("WITHDRAWAL"), eq(submitted.id()),
                eq(MERCHANT_ID), any());
    }

    @Test
    void payUsesEmvAmountWhenPresentAndMatchesRequest() {
        String emvWithAmount = TestPixCodes.staticEmv(PIX_KEY, "10.00");
        Withdrawal pending = Withdrawal.processing(MERCHANT_ID, Money.of("10.00"), Money.of("2.50"),
                null, PIX_KEY, WithdrawalType.BILL_PAYMENT, null);
        when(processingPersister.createBillPaymentWithdrawal(
                eq(MERCHANT_ID), eq(Money.of("10.00")), eq(Money.of("2.50")), eq(PIX_KEY),
                any(), any(), anyBoolean()))
                .thenReturn(new WithdrawalProcessingPersister.MerchantWithdrawal(pending, Money.of("90.00")));
        String ref = "PO" + UUID.randomUUID().toString().replace("-", "");
        when(payoutProvider.createPayout(any())).thenReturn(new PayoutProviderClient.PayoutResult(ref));
        when(submissionPersister.submit(any(), eq(ref), eq("MOCK")))
                .thenReturn(pending.submitToPsp(ref, "MOCK"));

        PvsPaymentUseCase.Result result = service.pay(command(emvWithAmount, "10.00"));

        assertThat(result.amount().amount()).isEqualByComparingTo("10.00");
        // EMV com valor → o request DEVE bater (10.00 == 10.00 passa).
        verify(processingPersister).createBillPaymentWithdrawal(
                eq(MERCHANT_ID), eq(Money.of("10.00")), eq(Money.of("2.50")), eq(PIX_KEY),
                any(), any(), anyBoolean());
    }

    @Test
    void payRejectsAmountMismatchWhenEmvHasValue() {
        String emvWithAmount = TestPixCodes.staticEmv(PIX_KEY, "10.00");

        assertThatThrownBy(() -> service.pay(command(emvWithAmount, "20.00")))
                .isInstanceOf(PvsAmountException.class)
                .satisfies(e -> assertThat(((PvsAmountException) e).code()).isEqualTo("pvs_amount_mismatch"));

        verify(processingPersister, never()).createBillPaymentWithdrawal(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(payoutProvider, never()).createPayout(any());
    }

    @Test
    void payRejectsMissingAmountWhenEmvHasNoValue() {
        assertThatThrownBy(() -> service.pay(command(PIX_CODE, null)))
                .isInstanceOf(PvsAmountException.class)
                .satisfies(e -> assertThat(((PvsAmountException) e).code()).isEqualTo("pvs_amount_required"));

        verify(processingPersister, never()).createBillPaymentWithdrawal(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void payWhenProviderFailsSyncReversesDebitAndThrows() {
        stubPhaseA();
        when(payoutProvider.createPayout(any()))
                .thenThrow(new IllegalStateException("Mock payout: falha simulada"));

        assertThatThrownBy(() -> service.pay(command(PIX_CODE, "40.00")))
                .isInstanceOf(IllegalStateException.class);

        verify(submissionPersister, never()).submit(any(), any(), any());
        // Estorno em transação própria — nenhum débito fica pendurado.
        verify(settlement).settleFailedByWithdrawalId(any(), eq("Falha no PSP: Mock payout: falha simulada"));
    }

    @Test
    void payRejectsIdorWhenAuthenticatedMerchantDiffers() {
        Merchant other = Merchant.restore(UUID.randomUUID(), "Loja B LTDA", "b@example.com", null,
                null, MerchantStatus.ACTIVE, new PricingPlan(Money.of("0.50"), Money.of("69.95")),
                Money.of("0.00"), false, NOW);
        when(authenticatedMerchant.currentMerchant()).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.pay(command(PIX_CODE, "40.00")))
                .isInstanceOf(MerchantAccessDeniedException.class);

        verify(processingPersister, never()).createBillPaymentWithdrawal(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(payoutProvider, never()).createPayout(any());
    }

    @Test
    void limitsComputesUsedAndRemaining() {
        when(withdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(
                eq(MERCHANT_ID), eq(WithdrawalType.BILL_PAYMENT), any()))
                .thenReturn(new BigDecimal("1234.50"));

        PvsPaymentUseCase.Limits limits = service.limits(MERCHANT_ID);

        assertThat(limits.dailyCap().amount()).isEqualByComparingTo("5000.00");
        assertThat(limits.usedToday().amount()).isEqualByComparingTo("1234.50");
        assertThat(limits.remaining().amount()).isEqualByComparingTo("3765.50");
        assertThat(limits.maxPerPayment().amount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void limitsFloorsRemainingAtZero() {
        when(withdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(
                eq(MERCHANT_ID), eq(WithdrawalType.BILL_PAYMENT), any()))
                .thenReturn(new BigDecimal("9999.00"));

        PvsPaymentUseCase.Limits limits = service.limits(MERCHANT_ID);

        assertThat(limits.remaining().amount()).isEqualByComparingTo("0.00");
    }
}
