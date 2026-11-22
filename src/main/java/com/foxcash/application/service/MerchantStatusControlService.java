package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.port.in.MerchantStatusControlUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: controle master do status do lojista (Spec 16, ADR-008).
 *
 * <p>Suspender → {@code SUSPENDED} (nao processa pagamentos nem saca);
 * liberar → {@code ACTIVE}. Decisao do operador. Cada acao gera trilha de
 * auditoria {@code RISK_ADJUSTMENT} (Spec 37) na mesma transacao, com
 * {@code before/after.status}.
 *
 * <p>Autorizacao (Spec 69): as acoes exigem {@code merchant.suspend/release} —
 * permissao OPERATOR, concedida a todo master ativo (guard na mesma transacao).
 */
@Service
public class MerchantStatusControlService implements MerchantStatusControlUseCase {

    private final MerchantRepository merchantRepository;
    private final MasterAuditRecorder auditRecorder;
    private final MasterPermissionGuard permissionGuard;

    public MerchantStatusControlService(MerchantRepository merchantRepository,
                                        MasterAuditRecorder auditRecorder,
                                        MasterPermissionGuard permissionGuard) {
        this.merchantRepository = merchantRepository;
        this.auditRecorder = auditRecorder;
        this.permissionGuard = permissionGuard;
    }

    @Override
    @Transactional
    public void suspend(UUID merchantId, UUID actorId, String ip) {
        permissionGuard.requirePermission(actorId, MasterPermission.MERCHANT_SUSPEND);
        Merchant merchant = requireMerchant(merchantId);
        Map<String, Object> before = status(merchant);
        merchant.suspend();
        // Spec 128: humano tocou o status → o automático para de agir (sticky).
        merchant.markRiskAutoManaged();
        merchantRepository.save(merchant);
        auditRecorder.record(actorId, MasterAuditAction.RISK_ADJUSTMENT, merchantId,
                "MERCHANT", merchantId, before, status(merchant), null, ip);
    }

    @Override
    @Transactional
    public void release(UUID merchantId, UUID actorId, String ip) {
        permissionGuard.requirePermission(actorId, MasterPermission.MERCHANT_RELEASE);
        Merchant merchant = requireMerchant(merchantId);
        Map<String, Object> before = status(merchant);
        merchant.activate();
        // Spec 128: humano tocou o status → o automático para de agir (sticky).
        merchant.markRiskAutoManaged();
        merchantRepository.save(merchant);
        auditRecorder.record(actorId, MasterAuditAction.RISK_ADJUSTMENT, merchantId,
                "MERCHANT", merchantId, before, status(merchant), null, ip);
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

    private Map<String, Object> status(Merchant merchant) {
        return Map.of("status", merchant.status().name());
    }
}
