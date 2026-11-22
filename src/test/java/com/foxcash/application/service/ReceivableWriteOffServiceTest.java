package com.foxcash.application.service;

import com.foxcash.domain.exception.AuditReasonRequiredException;
import com.foxcash.domain.exception.ReceivableNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.billing.ReceivableStatus;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.port.in.ReceivableWriteOffUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.ReceivableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
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
 * Testes do {@link ReceivableWriteOffService} (Spec 34): write-off → receivable
 * PAID + {@code CHARGEBACK_LOSS} no master ledger + auditoria (Spec 37); sem
 * reason → 400 {@code reason_required}; ja pago → 409; inexistente → 404.
 * Auditoria 2026-08-05 (M5): lock pessimista no lojista antes do ledger.
 */
class ReceivableWriteOffServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String IP = "192.168.1.10";

    private final ReceivableRepository receivableRepository = mock(ReceivableRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final MasterLedgerRecorder masterLedgerRecorder = mock(MasterLedgerRecorder.class);
    private final MasterAuditRecorder auditRecorder = mock(MasterAuditRecorder.class);
    private final MasterPermissionGuard permissionGuard = mock(MasterPermissionGuard.class);
    private final MasterStepUpGuard stepUpGuard = mock(MasterStepUpGuard.class);

    private ReceivableWriteOffService service;

    @BeforeEach
    void setUp() {
        service = new ReceivableWriteOffService(receivableRepository, merchantRepository,
                masterLedgerRecorder, auditRecorder, permissionGuard, stepUpGuard);
    }

    private Receivable receivable(ReceivableStatus status, String amount, String paid) {
        return Receivable.restore(UUID.randomUUID(), UUID.randomUUID(), ReceivableOrigin.CHARGEBACK,
                UUID.randomUUID(), Money.of(amount), Money.of(paid), status, NOW, null, NOW);
    }

    @Test
    void writeOffMarksPaidRecordsLossAndAudits() {
        Receivable debt = receivable(ReceivableStatus.OPEN, "100.00", "0.00");
        when(receivableRepository.findById(debt.id())).thenReturn(Optional.of(debt));

        ReceivableWriteOffUseCase.MoneyOutcome outcome = service.writeOff(ACTOR_ID, debt.id(), "Perda apos contato", IP);

        assertThat(outcome.loss().amount()).isEqualByComparingTo("100.00");
        assertThat(debt.status()).isEqualTo(ReceivableStatus.PAID);
        assertThat(debt.paidAt()).isNotNull();
        verify(receivableRepository).save(debt);
        verify(masterLedgerRecorder).record(eq(debt.merchantId()), eq(MasterLedgerEntryType.CHARGEBACK_LOSS),
                eq(Money.of("100.00")), eq("RECEIVABLE"), eq(debt.id()));
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.RECEIVABLE_WRITE_OFF),
                eq(debt.merchantId()), eq("RECEIVABLE"), eq(debt.id()),
                any(), any(), eq("Perda apos contato"), eq(IP));
    }

    @Test
    void writeOffPartialDebtRecordsOnlyRemainingAsLoss() {
        // Receivable PARTIAL (40 pago de 100): a perda e apenas o restante (60).
        Receivable debt = receivable(ReceivableStatus.PARTIAL, "100.00", "40.00");
        when(receivableRepository.findById(debt.id())).thenReturn(Optional.of(debt));

        ReceivableWriteOffUseCase.MoneyOutcome outcome = service.writeOff(ACTOR_ID, debt.id(), "Perda", IP);

        assertThat(outcome.loss().amount()).isEqualByComparingTo("60.00");
        verify(masterLedgerRecorder).record(eq(debt.merchantId()), eq(MasterLedgerEntryType.CHARGEBACK_LOSS),
                eq(Money.of("60.00")), eq("RECEIVABLE"), eq(debt.id()));
    }

    @Test
    void writeOffAcquiresMerchantLockBeforeLedger() {
        // Auditoria 2026-08-05 (M5): lock pessimista no lojista serializa o
        // balance_after do master ledger com os demais fluxos de receita e
        // impede write-off duplicado (o segundo vê PAID e responde 409).
        Receivable debt = receivable(ReceivableStatus.OPEN, "100.00", "0.00");
        when(receivableRepository.findById(debt.id())).thenReturn(Optional.of(debt));

        service.writeOff(ACTOR_ID, debt.id(), "Perda", IP);

        verify(merchantRepository).findByIdForUpdate(debt.merchantId());
    }

    @Test
    void writeOffWithoutReasonThrowsReasonRequired() {
        Receivable debt = receivable(ReceivableStatus.OPEN, "100.00", "0.00");
        when(receivableRepository.findById(debt.id())).thenReturn(Optional.of(debt));

        assertThatThrownBy(() -> service.writeOff(ACTOR_ID, debt.id(), "  ", IP))
                .isInstanceOf(AuditReasonRequiredException.class);

        verify(receivableRepository, never()).save(any());
        verify(masterLedgerRecorder, never()).record(any(), any(), any(), any(), any());
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void writeOffAlreadyPaidThrowsInvalidState() {
        Receivable debt = receivable(ReceivableStatus.PAID, "100.00", "100.00");
        when(receivableRepository.findById(debt.id())).thenReturn(Optional.of(debt));

        assertThatThrownBy(() -> service.writeOff(ACTOR_ID, debt.id(), "Perda", IP))
                .isInstanceOf(IllegalStateException.class);

        verify(receivableRepository, never()).save(any());
    }

    @Test
    void writeOffUnknownReceivableThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(receivableRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.writeOff(ACTOR_ID, id, "Perda", IP))
                .isInstanceOf(ReceivableNotFoundException.class);

        verify(receivableRepository, never()).save(any());
    }

    @Test
    void writeOffRequiresPermissionAndStepUpBeforeAnyEffect() {
        // Spec 69 (critérios 3/8): write-off é SENSÍVEL — permissão (todo master
        // ativo) + step-up MFA antes de qualquer efeito. Step-up negado → 403
        // step_up_required; o receivable NÃO é baixado nem auditado.
        com.foxcash.domain.model.master.Master actor = com.foxcash.domain.model.master.Master.restore(
                ACTOR_ID, "master", "hash", "Master", true, false,
                java.time.Instant.parse("2026-08-11T12:00:00Z"));
        when(permissionGuard.requirePermission(ACTOR_ID,
                com.foxcash.domain.model.master.MasterPermission.RECEIVABLE_WRITE_OFF))
                .thenReturn(actor);
        org.mockito.Mockito.doThrow(new com.foxcash.domain.exception.MasterStepUpRequiredException())
                .when(stepUpGuard).requireSteppedUp(actor,
                        com.foxcash.domain.model.master.MasterPermission.RECEIVABLE_WRITE_OFF);

        Receivable debt = receivable(ReceivableStatus.OPEN, "100.00", "0.00");
        when(receivableRepository.findById(debt.id())).thenReturn(Optional.of(debt));

        assertThatThrownBy(() -> service.writeOff(ACTOR_ID, debt.id(), "Perda", IP))
                .isInstanceOf(com.foxcash.domain.exception.MasterStepUpRequiredException.class);

        verify(receivableRepository, never()).save(any());
        verify(masterLedgerRecorder, never()).record(any(), any(), any(), any(), any());
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
