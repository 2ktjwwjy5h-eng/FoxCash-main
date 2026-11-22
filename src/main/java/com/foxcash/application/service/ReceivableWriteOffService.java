package com.foxcash.application.service;

import com.foxcash.domain.exception.AuditReasonRequiredException;
import com.foxcash.domain.exception.ReceivableNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableStatus;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.port.in.ReceivableWriteOffUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.ReceivableRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Baixa (write-off) de receivable pelo master (Spec 34) — dívida considerada
 * perda pela plataforma.
 *
 * <p>Ação manual do master (nunca automática): {@code reason} obrigatório (400
 * {@code reason_required}), receivable {@code OPEN}/{@code PARTIAL} → {@code
 * PAID} por baixa (409 se já {@code PAID}), entry {@code CHARGEBACK_LOSS} no
 * ledger do master (Spec 34) e trilha de auditoria {@code RECEIVABLE_WRITE_OFF}
 * (Spec 37) — tudo na mesma transação.
 *
 * <p>Autorizacao (Spec 69): exige {@code receivable.write_off} (OPERATOR — todo
 * master ativo) + **step-up MFA** (acao sensivel) antes de qualquer efeito.
 */
@Service
public class ReceivableWriteOffService implements ReceivableWriteOffUseCase {

    private final ReceivableRepository receivableRepository;
    private final MerchantRepository merchantRepository;
    private final MasterLedgerRecorder masterLedgerRecorder;
    private final MasterAuditRecorder auditRecorder;
    private final MasterPermissionGuard permissionGuard;
    private final MasterStepUpGuard stepUpGuard;

    public ReceivableWriteOffService(ReceivableRepository receivableRepository,
                                     MerchantRepository merchantRepository,
                                     MasterLedgerRecorder masterLedgerRecorder,
                                     MasterAuditRecorder auditRecorder,
                                     MasterPermissionGuard permissionGuard,
                                     MasterStepUpGuard stepUpGuard) {
        this.receivableRepository = receivableRepository;
        this.merchantRepository = merchantRepository;
        this.masterLedgerRecorder = masterLedgerRecorder;
        this.auditRecorder = auditRecorder;
        this.permissionGuard = permissionGuard;
        this.stepUpGuard = stepUpGuard;
    }

    @Override
    @Transactional
    public MoneyOutcome writeOff(UUID actorId, UUID receivableId, String reason, String ip) {
        if (reason == null || reason.isBlank()) {
            throw new AuditReasonRequiredException();
        }
        // Spec 69: permissao + step-up MFA (acao sensivel) antes de qualquer efeito.
        Master actor = permissionGuard.requirePermission(actorId, MasterPermission.RECEIVABLE_WRITE_OFF);
        stepUpGuard.requireSteppedUp(actor, MasterPermission.RECEIVABLE_WRITE_OFF);

        Receivable receivable = receivableRepository.findById(receivableId)
                .orElseThrow(() -> new ReceivableNotFoundException(receivableId));
        if (receivable.status() == ReceivableStatus.PAID) {
            throw new IllegalStateException("Receivable " + receivableId + " já está PAID");
        }

        // Auditoria 2026-08-05 (M5): lock pessimista no lojista antes de gravar o
        // CHARGEBACK_LOSS — serializa o balance_after do master ledger com os
        // demais fluxos de receita e impede write-off duplicado do mesmo
        // receivable (o segundo vê PAID e responde 409).
        merchantRepository.findByIdForUpdate(receivable.merchantId());

        Money loss = receivable.remaining();
        Map<String, Object> before = Map.of(
                "status", receivable.status().name(),
                "amount", receivable.amount().amount(),
                "paidAmount", receivable.paidAmount().amount());
        Map<String, Object> after = Map.of(
                "status", ReceivableStatus.PAID.name(),
                "amount", receivable.amount().amount(),
                "paidAmount", receivable.amount().amount());

        receivable.markPaid(Instant.now());
        receivableRepository.save(receivable);

        // Ledger do master (Spec 34): a baixa é perda da plataforma.
        masterLedgerRecorder.record(receivable.merchantId(), MasterLedgerEntryType.CHARGEBACK_LOSS,
                loss, "RECEIVABLE", receivable.id());

        // Auditoria (Spec 37): mesma transação, actor = master.id() do principal.
        auditRecorder.record(actorId, MasterAuditAction.RECEIVABLE_WRITE_OFF,
                receivable.merchantId(), "RECEIVABLE", receivable.id(),
                before, after, reason, ip);

        return new MoneyOutcome(loss);
    }
}
