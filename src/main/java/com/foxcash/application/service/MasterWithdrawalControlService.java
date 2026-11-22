package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.port.in.MasterWithdrawalControlUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: controle master de saques por lojista (Spec 14).
 *
 * <p>Bloqueia/libera a flag {@code withdrawals_blocked} — impede novos
 * saques (nao estorna os ja efetivados). Cada acao gera trilha de auditoria
 * {@code WITHDRAWALS_BLOCK}/{@code WITHDRAWALS_RELEASE} (Spec 37) na mesma
 * transacao, com {@code before/after.withdrawalsBlocked}.
 *
 * <p>Autorizacao (Spec 69): as acoes exigem {@code withdrawal.block/release} —
 * permissao OPERATOR, concedida a todo master ativo (guard na mesma transacao).
 */
@Service
public class MasterWithdrawalControlService implements MasterWithdrawalControlUseCase {

    private final MerchantRepository merchantRepository;
    private final MasterAuditRecorder auditRecorder;
    private final MasterPermissionGuard permissionGuard;

    public MasterWithdrawalControlService(MerchantRepository merchantRepository,
                                          MasterAuditRecorder auditRecorder,
                                          MasterPermissionGuard permissionGuard) {
        this.merchantRepository = merchantRepository;
        this.auditRecorder = auditRecorder;
        this.permissionGuard = permissionGuard;
    }

    @Override
    @Transactional
    public void block(UUID merchantId, UUID actorId, String ip) {
        permissionGuard.requirePermission(actorId, MasterPermission.WITHDRAWAL_BLOCK);
        Merchant merchant = requireMerchant(merchantId);
        Map<String, Object> before = state(merchant);
        merchant.blockWithdrawals();
        merchantRepository.save(merchant);
        auditRecorder.record(actorId, MasterAuditAction.WITHDRAWALS_BLOCK,
                merchantId, "MERCHANT", merchantId, before, state(merchant), null, ip);
    }

    @Override
    @Transactional
    public void release(UUID merchantId, UUID actorId, String ip) {
        permissionGuard.requirePermission(actorId, MasterPermission.WITHDRAWAL_RELEASE);
        Merchant merchant = requireMerchant(merchantId);
        Map<String, Object> before = state(merchant);
        merchant.releaseWithdrawals();
        merchantRepository.save(merchant);
        auditRecorder.record(actorId, MasterAuditAction.WITHDRAWALS_RELEASE,
                merchantId, "MERCHANT", merchantId, before, state(merchant), null, ip);
    }

    /**
     * SEC-19 (revisão de segurança 2026-08-11): o save do merchant grava TODOS
     * os campos (sem @DynamicUpdate), incluindo {@code wallet_balance} — uma
     * leitura sem lock (findById) salvaria a snapshot stale por cima de um
     * débito/crédito concorrente (saque/webhook/chargeback, que usam FOR
     * UPDATE), revertendo a movimentação. O lock pessimista serializa a
     * mutação com os fluxos de dinheiro — mesmo padrão do SEC-15.
     */
    private Merchant requireMerchant(UUID merchantId) {
        return merchantRepository.findByIdForUpdate(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
    }

    private Map<String, Object> state(Merchant merchant) {
        return Map.of("withdrawalsBlocked", merchant.withdrawalsBlocked());
    }
}
