package com.foxcash.application.service;

import com.foxcash.domain.exception.AuditReasonRequiredException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantConfig;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.in.MerchantConfigUseCase;
import com.foxcash.domain.port.out.MerchantConfigProvider;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantConfigRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantConfigResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: config fina por lojista + controle de conta (Spec 98 — D1).
 *
 * <p>Toda escrita roda sob {@code ROLE_MASTER} com RBAC (Spec 69 —
 * {@link MasterPermissionGuard}), auditoria na MESMA transação (Spec 37 —
 * {@link MasterAuditRecorder}) e step-up TOTP nas ações sensíveis (Spec 69 —
 * {@link MasterStepUpGuard}): {@code merchant.debit} e {@code merchant.ban}.
 *
 * <p><b>SEC-19:</b> todo {@code save} do lojista parte de
 * {@code findWithConfigForUpdate} (lock pessimista) — o save grava TODOS os
 * campos (sem {@code @DynamicUpdate}), incluindo {@code wallet_balance}; sem o
 * lock, uma snapshot stale sobrescreveria débito/crédito concorrente
 * (saque/webhook/chargeback, que usam FOR UPDATE).
 */
@Service
public class MerchantConfigService implements MerchantConfigUseCase {

    private final MerchantConfigProvider configProvider;
    private final WalletLedgerRecorder walletLedgerRecorder;
    private final MasterLedgerRecorder masterLedgerRecorder;
    private final MasterAuditRecorder auditRecorder;
    private final MasterPermissionGuard permissionGuard;
    private final MasterStepUpGuard stepUpGuard;

    public MerchantConfigService(MerchantConfigProvider configProvider,
                                 WalletLedgerRecorder walletLedgerRecorder,
                                 MasterLedgerRecorder masterLedgerRecorder,
                                 MasterAuditRecorder auditRecorder,
                                 MasterPermissionGuard permissionGuard,
                                 MasterStepUpGuard stepUpGuard) {
        this.configProvider = configProvider;
        this.walletLedgerRecorder = walletLedgerRecorder;
        this.masterLedgerRecorder = masterLedgerRecorder;
        this.auditRecorder = auditRecorder;
        this.permissionGuard = permissionGuard;
        this.stepUpGuard = stepUpGuard;
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantConfigResponse getConfig(UUID merchantId) {
        MerchantConfig config = configProvider.findWithConfig(merchantId)
                .map(Merchant::config)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        return MerchantConfigResponse.from(config);
    }

    @Override
    @Transactional
    public MerchantConfigResponse updateConfig(UUID merchantId, MerchantConfigRequest request,
                                               UUID actorId, String ip) {
        permissionGuard.requirePermission(actorId, MasterPermission.MERCHANT_CONFIG);
        Merchant merchant = requireMerchantForUpdate(merchantId);
        Map<String, Object> before = configMap(merchant.config());
        MerchantConfig merged = request.mergeWith(merchant.config());
        merchant.updateConfig(merged); // valida o conjunto resultante (D3)
        // Spec 128: humano tocou a config → o automático para de agir (sticky).
        merchant.markRiskAutoManaged();
        configProvider.save(merchant);
        auditRecorder.record(actorId, MasterAuditAction.MERCHANT_CONFIG_UPDATED, merchantId,
                "MERCHANT", merchantId, before, configMap(merchant.config()), null, ip);
        return MerchantConfigResponse.from(merchant.config());
    }

    @Override
    @Transactional
    public DebitOutcome debit(UUID merchantId, Money amount, String reason, UUID actorId, String ip) {
        if (reason == null || reason.isBlank()) {
            throw new AuditReasonRequiredException();
        }
        if (amount == null || amount.amount().signum() <= 0) {
            throw new IllegalArgumentException("amount deve ser maior que zero");
        }
        // Spec 69: permissao + step-up MFA (acao sensivel) antes de qualquer efeito.
        Master actor = permissionGuard.requirePermission(actorId, MasterPermission.MERCHANT_DEBIT);
        stepUpGuard.requireSteppedUp(actor, MasterPermission.MERCHANT_DEBIT);

        Merchant merchant = requireMerchantForUpdate(merchantId);
        Map<String, Object> before = wallet(merchant);
        merchant.debit(amount); // saldo pode ficar negativo (decisão — padrão estorno Spec 26)

        // Ledger duplo na MESMA transação (Specs 27/34): referenceId novo por
        // débito — a constraint uq_*_ledger_ref é (merchant, type, refType,
        // refId); merchantId fixo impediria o 2º débito do mesmo lojista.
        UUID debitId = UUID.randomUUID();
        walletLedgerRecorder.record(merchant, WalletLedgerEntryType.ADMIN_DEBIT,
                amount, "ADMIN_DEBIT", debitId);
        masterLedgerRecorder.record(merchantId, MasterLedgerEntryType.ADMIN_DEBIT,
                amount, "ADMIN_DEBIT", debitId);

        configProvider.save(merchant);
        auditRecorder.record(actorId, MasterAuditAction.ADMIN_DEBIT, merchantId,
                "MERCHANT", merchantId, before, wallet(merchant), reason, ip);
        return new DebitOutcome(merchant.walletBalance(), amount);
    }

    @Override
    @Transactional
    public void markHoneypot(UUID merchantId, boolean enabled, UUID actorId, String ip) {
        permissionGuard.requirePermission(actorId, MasterPermission.MERCHANT_CONFIG);
        Merchant merchant = requireMerchantForUpdate(merchantId);
        Map<String, Object> before = honeypot(merchant);
        merchant.markHoneypot(enabled);
        // Spec 128: humano tocou → o automático para de agir (sticky).
        merchant.markRiskAutoManaged();
        configProvider.save(merchant);
        auditRecorder.record(actorId,
                enabled ? MasterAuditAction.HONEYPOT_MARKED : MasterAuditAction.HONEYPOT_UNMARKED,
                merchantId, "MERCHANT", merchantId, before, honeypot(merchant), null, ip);
    }

    @Override
    @Transactional
    public void deactivate(UUID merchantId, UUID actorId, String ip) {
        permissionGuard.requirePermission(actorId, MasterPermission.MERCHANT_CONFIG);
        Merchant merchant = requireMerchantForUpdate(merchantId);
        Map<String, Object> before = status(merchant);
        merchant.deactivate();
        // Spec 128: humano tocou o status → o automático para de agir (sticky).
        merchant.markRiskAutoManaged();
        configProvider.save(merchant);
        auditRecorder.record(actorId, MasterAuditAction.MERCHANT_DEACTIVATED, merchantId,
                "MERCHANT", merchantId, before, status(merchant), null, ip);
    }

    @Override
    @Transactional
    public void activate(UUID merchantId, UUID actorId, String ip) {
        permissionGuard.requirePermission(actorId, MasterPermission.MERCHANT_CONFIG);
        Merchant merchant = requireMerchantForUpdate(merchantId);
        Map<String, Object> before = status(merchant);
        merchant.activate();
        // Spec 128: humano tocou o status → o automático para de agir (sticky).
        merchant.markRiskAutoManaged();
        configProvider.save(merchant);
        auditRecorder.record(actorId, MasterAuditAction.MERCHANT_ACTIVATED, merchantId,
                "MERCHANT", merchantId, before, status(merchant), null, ip);
    }

    @Override
    @Transactional
    public void ban(UUID merchantId, String reason, UUID actorId, String ip) {
        if (reason == null || reason.isBlank()) {
            throw new AuditReasonRequiredException();
        }
        // Spec 69: permissao + step-up MFA (acao sensivel) antes de qualquer efeito.
        Master actor = permissionGuard.requirePermission(actorId, MasterPermission.MERCHANT_BAN);
        stepUpGuard.requireSteppedUp(actor, MasterPermission.MERCHANT_BAN);

        Merchant merchant = requireMerchantForUpdate(merchantId);
        if (merchant.status() == MerchantStatus.BANNED) {
            // Irreversível: um 2º ban do mesmo lojista é conflito de estado (409).
            throw new IllegalStateException("Merchant " + merchantId + " já está BANNED");
        }
        Map<String, Object> before = status(merchant);
        merchant.ban();
        // Spec 128: humano tocou o status → o automático para de agir (sticky).
        merchant.markRiskAutoManaged();
        configProvider.save(merchant);
        auditRecorder.record(actorId, MasterAuditAction.MERCHANT_BANNED, merchantId,
                "MERCHANT", merchantId, before, status(merchant), reason, ip);
    }

    /** SEC-19: toda escrita do lojista parte do lock pessimista (findByIdForUpdate). */
    private Merchant requireMerchantForUpdate(UUID merchantId) {
        return configProvider.findWithConfigForUpdate(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
    }

    private Map<String, Object> status(Merchant merchant) {
        return Map.of("status", merchant.status().name());
    }

    private Map<String, Object> honeypot(Merchant merchant) {
        return Map.of("honeypot", merchant.config().honeypot());
    }

    private Map<String, Object> wallet(Merchant merchant) {
        return Map.of("walletBalance", merchant.walletBalance().amount());
    }

    private Map<String, Object> configMap(MerchantConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pixFeeType", config.pixFeeType() == null ? null : config.pixFeeType().name());
        map.put("pixFeeFixed", config.pixFeeFixed() == null ? null : config.pixFeeFixed().amount());
        map.put("pixFeePercent", config.pixFeePercent());
        map.put("pixFeeThreshold", config.pixFeeThreshold() == null ? null : config.pixFeeThreshold().amount());
        map.put("withdrawalFee", config.withdrawalFee() == null ? null : config.withdrawalFee().amount());
        map.put("pixMin", config.pixMin() == null ? null : config.pixMin().amount());
        map.put("pixMax", config.pixMax() == null ? null : config.pixMax().amount());
        map.put("withdrawalMin", config.withdrawalMin() == null ? null : config.withdrawalMin().amount());
        map.put("withdrawalMax", config.withdrawalMax() == null ? null : config.withdrawalMax().amount());
        map.put("rateLimitRps", config.rateLimitRps());
        map.put("rateLimitRpm", config.rateLimitRpm());
        map.put("apiBlocked", config.apiBlocked());
        map.put("honeypot", config.honeypot());
        return map;
    }
}
