package com.foxcash.application.service;

import com.foxcash.domain.exception.DestinationInactiveException;
import com.foxcash.domain.exception.DestinationNotFoundException;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.port.in.WithdrawalUseCase;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.domain.port.out.WithdrawalDestinationRepository;
import com.foxcash.infrastructure.config.BillingProperties;
import com.foxcash.infrastructure.config.PricingProperties;
import com.foxcash.infrastructure.config.PspProperties;
import com.foxcash.infrastructure.security.AuthenticatedMerchantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link WithdrawalService} (Spec 14 + Spec 32 + Spec 36 + B-04): o
 * orquestrador valida o destino (404/409) e coordena as 2 fases — Fase A
 * (transação própria: débito + PROCESSING sem external_reference + ledger, via
 * {@link WithdrawalProcessingPersister}) e Fase B (PSP FORA da transação:
 * {@link WithdrawalSubmissionPersister} grava a referência em REQUIRES_NEW;
 * falha síncrona → estorno via {@link WithdrawalSettlementService}).
 */
class WithdrawalServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID DESTINATION_ID = UUID.randomUUID();

    private final WithdrawalDestinationRepository destinationRepository = mock(WithdrawalDestinationRepository.class);
    private final BillingProperties billingProperties = new BillingProperties();
    private final PricingProperties pricingProperties = new PricingProperties();
    private final PayoutProviderClient payoutProvider = mock(PayoutProviderClient.class);
    private final PspProperties pspProperties = new PspProperties();
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final WithdrawalProcessingPersister processingPersister = mock(WithdrawalProcessingPersister.class);
    private final WithdrawalSubmissionPersister submissionPersister = mock(WithdrawalSubmissionPersister.class);
    private final WithdrawalSettlementService settlement = mock(WithdrawalSettlementService.class);
    private final AuthenticatedMerchantResolver authenticatedMerchant = mock(AuthenticatedMerchantResolver.class);

    private WithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new WithdrawalService(destinationRepository, billingProperties, pricingProperties,
                payoutProvider, pspProperties, activityRecorder, processingPersister, submissionPersister,
                settlement, authenticatedMerchant);
    }

    private WithdrawalDestination destination(boolean active) {
        return new WithdrawalDestination(DESTINATION_ID, MERCHANT_ID, WithdrawalDestinationType.PIX_CPF,
                "52998224725", null, null, null, null, "Conta pessoal", active, NOW, NOW);
    }

    private void stubOwnedActiveDestination() {
        when(destinationRepository.findByIdAndMerchantId(DESTINATION_ID, MERCHANT_ID))
                .thenReturn(Optional.of(destination(true)));
    }

    private Withdrawal processingWithoutReference() {
        return Withdrawal.processing(MERCHANT_ID, Money.of("40.00"), Money.of("2.50"), DESTINATION_ID,
                "PIX_CPF:52998224725", WithdrawalType.MERCHANT, null);
    }

    private Withdrawal submittedWithReference(Withdrawal pending) {
        return pending.submitToPsp("PO" + UUID.randomUUID().toString().replace("-", ""), "MOCK");
    }

    private WithdrawalUseCase.Command command(String amount) {
        return new WithdrawalUseCase.Command(MERCHANT_ID, Money.of(amount), DESTINATION_ID);
    }

    @Test
    void withdrawSuccessCoordinatesPhaseAAndPhaseBAndReturnsResult() {
        Withdrawal pending = processingWithoutReference();
        String ref = "PO" + UUID.randomUUID().toString().replace("-", "");
        stubOwnedActiveDestination();
        when(processingPersister.createMerchantWithdrawal(MERCHANT_ID, Money.of("40.00"), Money.of("2.50"),
                destination(true), billingProperties.isBlockWithdrawalsOnDebt()))
                .thenReturn(new WithdrawalProcessingPersister.MerchantWithdrawal(pending, Money.of("60.00")));
        when(payoutProvider.createPayout(any())).thenReturn(new PayoutProviderClient.PayoutResult(ref));
        Withdrawal submitted = pending.submitToPsp(ref, "MOCK");
        when(submissionPersister.submit(eq(pending.id()), eq(ref), eq("MOCK"))).thenReturn(submitted);

        WithdrawalUseCase.Result result = service.withdraw(command("40.00"));

        assertThat(result.amount().amount()).isEqualByComparingTo("40.00");
        assertThat(result.walletBalance().amount()).isEqualByComparingTo("60.00");
        assertThat(result.withdrawalId()).isEqualTo(submitted.id());

        // PSP chamado com o MESMO withdrawal.id da Fase A (idempotente no adquirente).
        ArgumentCaptor<PayoutProviderClient.PayoutCommand> cmdCaptor =
                ArgumentCaptor.forClass(PayoutProviderClient.PayoutCommand.class);
        verify(payoutProvider).createPayout(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().withdrawalId()).isEqualTo(pending.id());
        assertThat(cmdCaptor.getValue().destination().pixKey()).isEqualTo("52998224725");
        assertThat(cmdCaptor.getValue().amount().amount()).isEqualByComparingTo("40.00");

        // Referência gravada em transação própria (REQUIRES_NEW).
        verify(submissionPersister).submit(pending.id(), ref, "MOCK");
        // Relatório de atividades após a Fase B.
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.WITHDRAWAL_REQUESTED), eq("WITHDRAWAL"), eq(submitted.id()),
                eq(MERCHANT_ID), any());
    }

    @Test
    void withdrawWhenProviderFailsSyncThrowsAndReversesDebit() {
        // Falha síncrona do PSP (timeout/5xx) → a Fase A JÁ commitou o PROCESSING;
        // o débito é estornado e o saque vira FAILED (mesmo fluxo do settlement).
        Withdrawal pending = processingWithoutReference();
        stubOwnedActiveDestination();
        when(processingPersister.createMerchantWithdrawal(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new WithdrawalProcessingPersister.MerchantWithdrawal(pending, Money.of("60.00")));
        when(payoutProvider.createPayout(any()))
                .thenThrow(new IllegalStateException("Mock payout: falha simulada"));

        assertThatThrownBy(() -> service.withdraw(command("40.00")))
                .isInstanceOf(IllegalStateException.class);

        verify(submissionPersister, never()).submit(any(), any(), any());
        // Estorno em transação própria — nenhum débito fica pendurado.
        verify(settlement).settleFailedByWithdrawalId(pending.id(), "Falha no PSP: Mock payout: falha simulada");
    }

    @Test
    void noReversalWhenPspAlreadyAcceptedAndLaterStepFails() {
        // Auditoria 2026-08-05: o estorno cobre APENAS a falha SÍNCRONA do PSP — se
        // o PSP já aceitou (submit OK), uma falha posterior (activityRecorder) NÃO
        // estorna um saque já pago no adquirente (evita duplo pagamento/ghost).
        Withdrawal pending = processingWithoutReference();
        String ref = "PO" + UUID.randomUUID().toString().replace("-", "");
        stubOwnedActiveDestination();
        when(processingPersister.createMerchantWithdrawal(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new WithdrawalProcessingPersister.MerchantWithdrawal(pending, Money.of("60.00")));
        when(payoutProvider.createPayout(any())).thenReturn(new PayoutProviderClient.PayoutResult(ref));
        when(submissionPersister.submit(pending.id(), ref, "MOCK"))
                .thenReturn(pending.submitToPsp(ref, "MOCK"));
        doThrow(new IllegalStateException("falha no recorder"))
                .when(activityRecorder).record(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.withdraw(command("40.00")))
                .isInstanceOf(IllegalStateException.class);

        // Saque já submetido ao PSP: nenhum estorno indevido.
        verify(submissionPersister).submit(pending.id(), ref, "MOCK");
        verify(settlement, never()).settleFailedByWithdrawalId(any(), any());
    }

    @Test
    void withdrawWithoutDestinationIdThrowsBadRequest() {
        assertThatThrownBy(() -> service.withdraw(
                new WithdrawalUseCase.Command(MERCHANT_ID, Money.of("10.00"), null)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(destinationRepository, never()).findByIdAndMerchantId(any(), any());
        verify(processingPersister, never()).createMerchantWithdrawal(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void withdrawWithDestinationOfAnotherMerchantThrowsNotFound() {
        // findByIdAndMerchantId vazio = destino inexistente OU de outro lojista
        // (404 — não revela existência, privacidade da Spec 31).
        when(destinationRepository.findByIdAndMerchantId(DESTINATION_ID, MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(command("10.00")))
                .isInstanceOf(DestinationNotFoundException.class);

        verify(processingPersister, never()).createMerchantWithdrawal(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void withdrawWithInactiveDestinationThrowsConflict() {
        when(destinationRepository.findByIdAndMerchantId(DESTINATION_ID, MERCHANT_ID))
                .thenReturn(Optional.of(destination(false)));

        assertThatThrownBy(() -> service.withdraw(command("10.00")))
                .isInstanceOf(DestinationInactiveException.class);

        verify(processingPersister, never()).createMerchantWithdrawal(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void withdrawForwardsBlockOnDebtConfigurationToPhaseA() {
        // A configuração de inadimplência é passada à Fase A (validação sob lock).
        BillingProperties permissive = new BillingProperties();
        permissive.setBlockWithdrawalsOnDebt(false);
        service = new WithdrawalService(destinationRepository, permissive, pricingProperties,
                payoutProvider, pspProperties, activityRecorder, processingPersister, submissionPersister,
                settlement, authenticatedMerchant);

        Withdrawal pending = processingWithoutReference();
        stubOwnedActiveDestination();
        when(processingPersister.createMerchantWithdrawal(MERCHANT_ID, Money.of("40.00"), Money.of("2.50"),
                destination(true), false))
                .thenReturn(new WithdrawalProcessingPersister.MerchantWithdrawal(pending, Money.of("60.00")));
        when(payoutProvider.createPayout(any())).thenReturn(
                new PayoutProviderClient.PayoutResult("PO" + UUID.randomUUID().toString().replace("-", "")));
        when(submissionPersister.submit(eq(pending.id()), any(), eq("MOCK")))
                .thenReturn(submittedWithReference(pending));

        WithdrawalUseCase.Result result = service.withdraw(command("40.00"));

        assertThat(result.walletBalance().amount()).isEqualByComparingTo("60.00");
        verify(processingPersister).createMerchantWithdrawal(MERCHANT_ID, Money.of("40.00"), Money.of("2.50"),
                destination(true), false);
        verify(submissionPersister).submit(eq(pending.id()), any(), eq("MOCK"));
    }

    // ---------- SEC-13 (revisão de segurança 2026-08-11): IDOR latente ----------

    @Test
    void authenticatedMerchantForAnotherMerchantIsDenied() {
        // SEC-13: o principal autenticado é o lojista A (resolver preenchido) mas
        // o merchantId do comando aponta para o lojista B → 403 fechado. O serviço
        // não pode confiar cegamente no merchantId do comando (IDOR latente; o
        // controller já protege via MerchantAccessGuard — defesa em profundidade).
        com.foxcash.domain.model.merchant.Merchant other =
                com.foxcash.domain.model.merchant.Merchant.restore(
                        UUID.randomUUID(), "Loja B LTDA", null, "52998224725", null,
                        com.foxcash.domain.model.merchant.MerchantStatus.ACTIVE,
                        new com.foxcash.domain.model.pricing.PricingPlan(
                                com.foxcash.domain.model.Money.of("0.50"),
                                com.foxcash.domain.model.Money.of("69.95")),
                        com.foxcash.domain.model.Money.of("0.00"), false, Instant.now());
        when(authenticatedMerchant.currentMerchant()).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.withdraw(command("40.00")))
                .isInstanceOf(MerchantAccessDeniedException.class);
        verify(processingPersister, never()).createMerchantWithdrawal(any(), any(), any(), any(), anyBoolean());
        verify(payoutProvider, never()).createPayout(any());
    }

    @Test
    void unauthenticatedRequestFallsBackToRepository() {
        // SEC-13 (sem regressão): fora de request HTTP (jobs/testes), o resolver
        // está vazio → o serviço mantém o fallback ao repositório (comportamento
        // original — o admin/job interno decide o lojista).
        Withdrawal pending = processingWithoutReference();
        when(authenticatedMerchant.currentMerchant()).thenReturn(Optional.empty());
        stubOwnedActiveDestination();
        when(processingPersister.createMerchantWithdrawal(MERCHANT_ID, Money.of("40.00"), Money.of("2.50"),
                destination(true), billingProperties.isBlockWithdrawalsOnDebt()))
                .thenReturn(new WithdrawalProcessingPersister.MerchantWithdrawal(pending, Money.of("60.00")));
        when(payoutProvider.createPayout(any())).thenReturn(
                new PayoutProviderClient.PayoutResult("PO" + UUID.randomUUID().toString().replace("-", "")));
        when(submissionPersister.submit(eq(pending.id()), any(), eq("MOCK")))
                .thenReturn(submittedWithReference(pending));

        WithdrawalUseCase.Result result = service.withdraw(command("40.00"));

        assertThat(result.walletBalance().amount()).isEqualByComparingTo("60.00");
    }
}
