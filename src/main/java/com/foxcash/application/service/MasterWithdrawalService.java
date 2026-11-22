package com.foxcash.application.service;

import com.foxcash.domain.exception.AuditReasonRequiredException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.port.in.MasterWithdrawalUseCase;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Saque administrativo do master em nome do lojista (Spec 32) — bloqueio
 * "lacrado".
 *
 * <p>O master informa {@code destinationKey} e {@code reason} no request (não
 * acessa os destinos privados do lojista — privacidade da Spec 31). {@code
 * Merchant.adminWithdraw} valida APENAS saldo suficiente: o master recolhe de
 * lojista bloqueado/suspenso, nunca deixando saldo negativo.
 *
 * <p>Auditoria 2026-08-05 (B-04): PSP chamado **fora** da transação — mesmo
 * padrão do {@link WithdrawalService}. <b>Fase A</b>
 * ({@link WithdrawalProcessingPersister#createAdministrativeWithdrawal}) grava
 * {@code type=ADMINISTRATIVE} + {@code reason} + {@code WITHDRAWAL_DEBIT} no
 * ledger do lojista (Spec 27) + {@code ADMIN_RECOVERY} no ledger do master
 * (Spec 34) + trilha de auditoria {@code ADMINISTRATIVE_WITHDRAWAL} (Spec 37),
 * tudo atômico sob o lock pessimista. <b>Fase B</b> chama o PSP e grava a
 * referência em transação própria; falha síncrona → estorno + {@code FAILED}.
 *
 * <p>Autorizacao (Spec 69): exige {@code withdrawal.administrative} (OPERATOR —
 * todo master ativo) + **step-up MFA** (acao sensivel) antes de qualquer efeito.
 */
@Service
public class MasterWithdrawalService implements MasterWithdrawalUseCase {

    private final PayoutProviderClient payoutProvider;
    private final PspProperties pspProperties;
    private final WithdrawalProcessingPersister processingPersister;
    private final WithdrawalSubmissionPersister submissionPersister;
    private final WithdrawalSettlementService settlement;
    private final MasterPermissionGuard permissionGuard;
    private final MasterStepUpGuard stepUpGuard;

    public MasterWithdrawalService(PayoutProviderClient payoutProvider,
                                   PspProperties pspProperties,
                                   WithdrawalProcessingPersister processingPersister,
                                   WithdrawalSubmissionPersister submissionPersister,
                                   WithdrawalSettlementService settlement,
                                   MasterPermissionGuard permissionGuard,
                                   MasterStepUpGuard stepUpGuard) {
        this.payoutProvider = payoutProvider;
        this.pspProperties = pspProperties;
        this.processingPersister = processingPersister;
        this.submissionPersister = submissionPersister;
        this.settlement = settlement;
        this.permissionGuard = permissionGuard;
        this.stepUpGuard = stepUpGuard;
    }

    @Override
    public Result administrativeWithdraw(UUID merchantId, Money amount, String destinationKey,
                                         String reason, UUID actorId, String ip) {
        if (destinationKey == null || destinationKey.isBlank()) {
            throw new IllegalArgumentException("destinationKey é obrigatório no saque administrativo");
        }
        if (reason == null || reason.isBlank()) {
            throw new AuditReasonRequiredException();
        }

        // Spec 69: permissao (toda master ativo) + step-up MFA (acao sensivel) —
        // antes de QUALQUER efeito (nem o débito da Fase A).
        Master actor = permissionGuard.requirePermission(actorId, MasterPermission.WITHDRAWAL_ADMINISTRATIVE);
        stepUpGuard.requireSteppedUp(actor, MasterPermission.WITHDRAWAL_ADMINISTRATIVE);

        // Fase A (transação própria): adminWithdraw (bloqueio lacrado) + PROCESSING
        // sem external_reference + WITHDRAWAL_DEBIT + ADMIN_RECOVERY + auditoria.
        WithdrawalProcessingPersister.AdminWithdrawal pending = processingPersister
                .createAdministrativeWithdrawal(merchantId, amount, destinationKey, reason, actorId, ip);

        // Fase B (FORA da transação): PSP — mesmo fluxo do saque do vendedor (B-04).
        // Auditoria 2026-08-05: o estorno cobre APENAS a falha síncrona do PSP — se
        // o PSP já aceitou, nenhuma falha posterior estorna um saque pago.
        PayoutProviderClient.PayoutResult result;
        try {
            result = payoutProvider.createPayout(
                    new PayoutProviderClient.PayoutCommand(pending.withdrawal().id(), amount,
                            new PayoutProviderClient.PayoutDestination(destinationKey, null, null, null, null),
                            "saque administrativo"));
        } catch (RuntimeException e) {
            // Falha síncrona do PSP: estorno em transação própria (mesmo fluxo do
            // settlement FAILED) — nenhum débito fica pendurado nem saque fantasma.
            settlement.settleFailedByWithdrawalId(pending.withdrawal().id(),
                    "Falha no PSP: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            throw e;
        }
        Withdrawal submitted = submissionPersister.submit(pending.withdrawal().id(),
                result.externalReference(),
                // Spec 92 (roteamento pós-fallback): provedor que ACEITOU no resultado.
                result.provider() != null ? result.provider().toUpperCase()
                        : pspProperties.getPayoutProvider().toUpperCase());
        return new Result(submitted.id(), submitted.amount(), pending.walletBalance());
    }
}
