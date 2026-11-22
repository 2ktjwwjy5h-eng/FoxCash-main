package com.foxcash.application.service;

import com.foxcash.domain.exception.AuditReasonRequiredException;
import com.foxcash.domain.exception.MasterStepUpRequiredException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.port.in.MasterWithdrawalUseCase;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterWithdrawalService} (Spec 32 — bloqueio "lacrado";
 * Spec 36 — nasce PROCESSING via PSP; B-04 — PSP fora da transação): o
 * orquestrador valida destinationKey + reason (400) e coordena a Fase A
 * (via {@link WithdrawalProcessingPersister}, que registra type=ADMINISTRATIVE +
 * WITHDRAWAL_DEBIT + ADMIN_RECOVERY + auditoria na mesma transação) e a Fase B
 * (PSP fora da transação + {@link WithdrawalSubmissionPersister}; falha síncrona
 * → estorno via {@link WithdrawalSettlementService}).
 */
class MasterWithdrawalServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String IP = "192.168.1.10";

    private final PayoutProviderClient payoutProvider = mock(PayoutProviderClient.class);
    private final PspProperties pspProperties = new PspProperties();
    private final WithdrawalProcessingPersister processingPersister = mock(WithdrawalProcessingPersister.class);
    private final WithdrawalSubmissionPersister submissionPersister = mock(WithdrawalSubmissionPersister.class);
    private final WithdrawalSettlementService settlement = mock(WithdrawalSettlementService.class);
    private final MasterPermissionGuard permissionGuard = mock(MasterPermissionGuard.class);
    private final MasterStepUpGuard stepUpGuard = mock(MasterStepUpGuard.class);

    private MasterWithdrawalService service;

    @BeforeEach
    void setUp() {
        service = new MasterWithdrawalService(payoutProvider, pspProperties,
                processingPersister, submissionPersister, settlement, permissionGuard, stepUpGuard);
    }

    private Withdrawal processingWithoutReference() {
        // Spec 88: saque administrativo é ISENTO de taxa (fee = 0.00).
        return Withdrawal.processing(MERCHANT_ID, Money.of("40.00"), Money.of("0.00"), null,
                "ADMINISTRATIVE:52998224725", WithdrawalType.ADMINISTRATIVE, "Recolhimento de taxa");
    }

    @Test
    void administrativeWithdrawCoordinatesPhaseAAndPhaseBAndReturnsResult() {
        Withdrawal pending = processingWithoutReference();
        String ref = "PO" + UUID.randomUUID().toString().replace("-", "");
        when(processingPersister.createAdministrativeWithdrawal(MERCHANT_ID, Money.of("40.00"),
                "52998224725", "Recolhimento de taxa", ACTOR_ID, IP))
                .thenReturn(new WithdrawalProcessingPersister.AdminWithdrawal(pending, Money.of("60.00")));
        when(payoutProvider.createPayout(any())).thenReturn(new PayoutProviderClient.PayoutResult(ref));
        Withdrawal submitted = pending.submitToPsp(ref, "MOCK");
        when(submissionPersister.submit(eq(pending.id()), eq(ref), eq("MOCK"))).thenReturn(submitted);

        MasterWithdrawalUseCase.Result result = service.administrativeWithdraw(
                MERCHANT_ID, Money.of("40.00"), "52998224725", "Recolhimento de taxa", ACTOR_ID, IP);

        assertThat(result.walletBalance().amount()).isEqualByComparingTo("60.00");
        assertThat(result.withdrawalId()).isEqualTo(submitted.id());

        // Fase A executada com os parâmetros do request (auditoria + ledger na transação).
        verify(processingPersister).createAdministrativeWithdrawal(MERCHANT_ID, Money.of("40.00"),
                "52998224725", "Recolhimento de taxa", ACTOR_ID, IP);
        // PSP chamado com o MESMO id da Fase A.
        ArgumentCaptor<PayoutProviderClient.PayoutCommand> cmdCaptor =
                ArgumentCaptor.forClass(PayoutProviderClient.PayoutCommand.class);
        verify(payoutProvider).createPayout(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().withdrawalId()).isEqualTo(pending.id());
        assertThat(cmdCaptor.getValue().destination().pixKey()).isEqualTo("52998224725");
        verify(submissionPersister).submit(pending.id(), ref, "MOCK");
    }

    @Test
    void administrativeWithdrawWhenProviderFailsSyncThrowsAndReversesDebit() {
        Withdrawal pending = processingWithoutReference();
        when(processingPersister.createAdministrativeWithdrawal(any(), any(), any(), any(), any(), any()))
                .thenReturn(new WithdrawalProcessingPersister.AdminWithdrawal(pending, Money.of("60.00")));
        when(payoutProvider.createPayout(any()))
                .thenThrow(new IllegalStateException("Mock payout: falha simulada"));

        assertThatThrownBy(() -> service.administrativeWithdraw(
                MERCHANT_ID, Money.of("40.00"), "52998224725", "Recolhimento de taxa", ACTOR_ID, IP))
                .isInstanceOf(IllegalStateException.class);

        verify(submissionPersister, never()).submit(any(), any(), any());
        verify(settlement).settleFailedByWithdrawalId(pending.id(), "Falha no PSP: Mock payout: falha simulada");
    }

    @Test
    void administrativeWithdrawWithoutReasonOrDestinationKeyThrowsBadRequest() {
        // Spec 37: reason vazio em acao financeira → 400 reason_required.
        assertThatThrownBy(() -> service.administrativeWithdraw(
                MERCHANT_ID, Money.of("10.00"), "52998224725", "  ", ACTOR_ID, IP))
                .isInstanceOf(AuditReasonRequiredException.class);

        assertThatThrownBy(() -> service.administrativeWithdraw(
                MERCHANT_ID, Money.of("10.00"), "52998224725", null, ACTOR_ID, IP))
                .isInstanceOf(AuditReasonRequiredException.class);

        // destinationKey ausente continua 400 invalid_argument (argumento inválido).
        assertThatThrownBy(() -> service.administrativeWithdraw(
                MERCHANT_ID, Money.of("10.00"), null, "Recolhimento", ACTOR_ID, IP))
                .isInstanceOf(IllegalArgumentException.class);

        verify(processingPersister, never()).createAdministrativeWithdrawal(any(), any(), any(), any(), any(), any());
    }

    @Test
    void administrativeWithdrawRequiresPermissionAndStepUpBeforeAnyEffect() {
        // Spec 69 (critérios 3/8): saque administrativo é SENSÍVEL — permissão
        // (todo master ativo) + step-up MFA antes de QUALQUER efeito (nem o
        // débito da Fase A). O step-up negado → 403 step_up_required.
        Master actor = Master.restore(ACTOR_ID, "master", "hash", "Master", true, false,
                java.time.Instant.parse("2026-08-11T12:00:00Z"));
        when(permissionGuard.requirePermission(ACTOR_ID, MasterPermission.WITHDRAWAL_ADMINISTRATIVE))
                .thenReturn(actor);
        org.mockito.Mockito.doThrow(new MasterStepUpRequiredException())
                .when(stepUpGuard).requireSteppedUp(actor, MasterPermission.WITHDRAWAL_ADMINISTRATIVE);

        assertThatThrownBy(() -> service.administrativeWithdraw(
                MERCHANT_ID, Money.of("40.00"), "52998224725", "Recolhimento", ACTOR_ID, IP))
                .isInstanceOf(MasterStepUpRequiredException.class);

        // Nenhum efeito colateral: nem a Fase A (débito) nem o PSP.
        verify(processingPersister, never()).createAdministrativeWithdrawal(any(), any(), any(), any(), any(), any());
        verify(payoutProvider, never()).createPayout(any());
    }
}
