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
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.in.MerchantConfigUseCase;
import com.foxcash.domain.port.out.MerchantConfigProvider;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantConfigRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantConfigResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
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
 * Testes do {@link MerchantConfigService} (Spec 98 — T5.1): config PATCH parcial
 * com validação, activate/deactivate, ban irreversível, honeypot mark/unmark,
 * debit com step-up + ledger duplo + saldo negativo, RBAC 403, 404.
 */
class MerchantConfigServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String IP = "192.168.1.10";
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));
    private static final Master ACTOR = Master.restore(ACTOR_ID, "master", "hash", "Master", true, NOW);

    private final MerchantConfigProvider configProvider = mock(MerchantConfigProvider.class);
    private final WalletLedgerRecorder walletLedgerRecorder = mock(WalletLedgerRecorder.class);
    private final MasterLedgerRecorder masterLedgerRecorder = mock(MasterLedgerRecorder.class);
    private final MasterAuditRecorder auditRecorder = mock(MasterAuditRecorder.class);
    private final MasterPermissionGuard permissionGuard = mock(MasterPermissionGuard.class);
    private final MasterStepUpGuard stepUpGuard = mock(MasterStepUpGuard.class);

    private MerchantConfigService service;

    @BeforeEach
    void setUp() {
        service = new MerchantConfigService(configProvider, walletLedgerRecorder,
                masterLedgerRecorder, auditRecorder, permissionGuard, stepUpGuard);
    }

    private Merchant merchant(MerchantStatus status, Money balance, MerchantConfig config) {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", "loja@example.com", null, null,
                status, PLAN, balance, false, NOW, config);
    }

    private void grant(MasterPermission permission) {
        when(permissionGuard.requirePermission(ACTOR_ID, permission)).thenReturn(ACTOR);
    }

    @Test
    void getConfigReturnsStoredConfig() {
        MerchantConfig config = new MerchantConfig(MerchantConfig.PixFeeType.FIXED,
                Money.of("1.00"), null, null, Money.of("3.00"), null, null, null, null,
                null, null, false, false);
        when(configProvider.findWithConfig(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("0.00"), config)));

        MerchantConfigResponse response = service.getConfig(MERCHANT_ID);

        assertThat(response.pixFeeType()).isEqualTo("FIXED");
        assertThat(response.pixFeeFixed()).isEqualByComparingTo("1.00");
        assertThat(response.withdrawalFee()).isEqualByComparingTo("3.00");
    }

    @Test
    void getConfigUnknownMerchantThrows404() {
        when(configProvider.findWithConfig(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getConfig(MERCHANT_ID))
                .isInstanceOf(MerchantNotFoundException.class);
    }

    @Test
    void updateConfigAppliesPartialMergeAndAudits() {
        grant(MasterPermission.MERCHANT_CONFIG);
        MerchantConfig current = MerchantConfig.defaults();
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("0.00"), current)));

        MerchantConfigRequest request = new MerchantConfigRequest(
                "FIXED", new BigDecimal("1.25"), null, null, null, null, null, null, null,
                null, null, null, null);

        MerchantConfigResponse response = service.updateConfig(MERCHANT_ID, request, ACTOR_ID, IP);

        assertThat(response.pixFeeType()).isEqualTo("FIXED");
        assertThat(response.pixFeeFixed()).isEqualByComparingTo("1.25");
        // Campos ausentes preservados (PATCH) — apiBlocked/honeypot seguem false.
        assertThat(response.rateLimitRps()).isNull();
        verify(configProvider).save(any(Merchant.class));
        // L211 (EmptyObjectReturnValsMutator): o payload de auditoria contém o
        // mapa de config COMPLETO (antes e depois), não um mapa vazio.
        ArgumentCaptor<Map> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> after = ArgumentCaptor.forClass(Map.class);
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.MERCHANT_CONFIG_UPDATED),
                eq(MERCHANT_ID), eq("MERCHANT"), eq(MERCHANT_ID),
                before.capture(), after.capture(), any(), eq(IP));
        assertThat(before.getValue()).containsKeys("pixFeeType", "pixFeeFixed", "rateLimitRps", "honeypot");
        assertThat(after.getValue()).containsKey("pixFeeType");
    }

    @Test
    void updateConfigInvalidPercentRejected() {
        grant(MasterPermission.MERCHANT_CONFIG);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("0.00"), MerchantConfig.defaults())));

        MerchantConfigRequest request = new MerchantConfigRequest(
                "PERCENT", new BigDecimal("1.00"), new BigDecimal("150"), new BigDecimal("100.00"),
                null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.updateConfig(MERCHANT_ID, request, ACTOR_ID, IP))
                .isInstanceOf(IllegalArgumentException.class);
        verify(configProvider, never()).save(any(Merchant.class));
    }

    @Test
    void debitRequiresStepUpRecordsDualLedgerAndAllowsNegative() {
        grant(MasterPermission.MERCHANT_DEBIT);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("10.00"), MerchantConfig.defaults())));

        MerchantConfigUseCase.DebitOutcome outcome =
                service.debit(MERCHANT_ID, Money.of("25.00"), "Multa administrativa", ACTOR_ID, IP);

        // Saldo pode ficar negativo (decisão — padrão estorno Spec 26).
        assertThat(outcome.newBalance().amount()).isEqualByComparingTo("-15.00");
        assertThat(outcome.debitedAmount().amount()).isEqualByComparingTo("25.00");
        verify(stepUpGuard).requireSteppedUp(ACTOR, MasterPermission.MERCHANT_DEBIT);
        verify(walletLedgerRecorder).record(any(Merchant.class), eq(WalletLedgerEntryType.ADMIN_DEBIT),
                eq(Money.of("25.00")), eq("ADMIN_DEBIT"), any(UUID.class));
        verify(masterLedgerRecorder).record(eq(MERCHANT_ID), eq(MasterLedgerEntryType.ADMIN_DEBIT),
                eq(Money.of("25.00")), eq("ADMIN_DEBIT"), any(UUID.class));
        // L193 (EmptyObjectReturnValsMutator): o payload de auditoria do débito
        // contém o saldo (antes e depois), não um mapa vazio.
        ArgumentCaptor<Map> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> after = ArgumentCaptor.forClass(Map.class);
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.ADMIN_DEBIT),
                eq(MERCHANT_ID), eq("MERCHANT"), eq(MERCHANT_ID),
                before.capture(), after.capture(), eq("Multa administrativa"), eq(IP));
        assertThat(before.getValue()).containsKey("walletBalance");
        assertThat(after.getValue()).containsKey("walletBalance");
    }

    @Test
    void debitWithoutReasonThrowsReasonRequired() {
        grant(MasterPermission.MERCHANT_DEBIT);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("10.00"), MerchantConfig.defaults())));

        assertThatThrownBy(() -> service.debit(MERCHANT_ID, Money.of("5.00"), "  ", ACTOR_ID, IP))
                .isInstanceOf(AuditReasonRequiredException.class);
        verify(configProvider, never()).save(any(Merchant.class));
    }

    @Test
    void debitZeroOrNegativeAmountRejected() {
        grant(MasterPermission.MERCHANT_DEBIT);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("10.00"), MerchantConfig.defaults())));

        assertThatThrownBy(() -> service.debit(MERCHANT_ID, Money.of("0.00"), "Motivo", ACTOR_ID, IP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.debit(MERCHANT_ID, Money.of("-5.00"), "Motivo", ACTOR_ID, IP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markHoneypotAuditsMarked() {
        grant(MasterPermission.MERCHANT_CONFIG);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("0.00"), MerchantConfig.defaults())));

        service.markHoneypot(MERCHANT_ID, true, ACTOR_ID, IP);

        // L125 (VoidMethodCallMutator): markHoneypot NÃO pode ser removido — o
        // lojista salvo precisa estar marcado como honeypot.
        ArgumentCaptor<Merchant> saved = ArgumentCaptor.forClass(Merchant.class);
        verify(configProvider).save(saved.capture());
        assertThat(saved.getValue().config().honeypot()).isTrue();
        // L189 (EmptyObjectReturnValsMutator): o payload de auditoria contém o
        // flag honeypot (antes e depois).
        ArgumentCaptor<Map> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> after = ArgumentCaptor.forClass(Map.class);
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.HONEYPOT_MARKED),
                eq(MERCHANT_ID), eq("MERCHANT"), eq(MERCHANT_ID),
                before.capture(), after.capture(), any(), eq(IP));
        assertThat(before.getValue()).containsKey("honeypot");
        assertThat(after.getValue()).containsKey("honeypot");
    }

    @Test
    void markHoneypotAuditsUnmarked() {
        grant(MasterPermission.MERCHANT_CONFIG);
        MerchantConfig honeypot = new MerchantConfig(null, null, null, null, null, null, null, null, null,
                null, null, false, true);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("0.00"), honeypot)));

        service.markHoneypot(MERCHANT_ID, false, ACTOR_ID, IP);

        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.HONEYPOT_UNMARKED),
                eq(MERCHANT_ID), eq("MERCHANT"), eq(MERCHANT_ID), any(), any(), any(), eq(IP));
    }

    @Test
    void deactivateSetsInactiveAndAudits() {
        grant(MasterPermission.MERCHANT_CONFIG);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("0.00"), MerchantConfig.defaults())));

        service.deactivate(MERCHANT_ID, ACTOR_ID, IP);

        // L138 (VoidMethodCallMutator): deactivate NÃO pode ser removido — o
        // lojista salvo precisa estar INACTIVE.
        ArgumentCaptor<Merchant> saved = ArgumentCaptor.forClass(Merchant.class);
        verify(configProvider).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(MerchantStatus.INACTIVE);
        // L185 (EmptyObjectReturnValsMutator): o payload de auditoria contém o
        // status (antes e depois).
        ArgumentCaptor<Map> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> after = ArgumentCaptor.forClass(Map.class);
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.MERCHANT_DEACTIVATED),
                eq(MERCHANT_ID), eq("MERCHANT"), eq(MERCHANT_ID),
                before.capture(), after.capture(), any(), eq(IP));
        assertThat(before.getValue()).containsKey("status");
        assertThat(after.getValue()).containsKey("status");
    }

    @Test
    void activateSetsActiveAndAudits() {
        grant(MasterPermission.MERCHANT_CONFIG);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.INACTIVE, Money.of("0.00"), MerchantConfig.defaults())));

        service.activate(MERCHANT_ID, ACTOR_ID, IP);

        // L150 (VoidMethodCallMutator): activate NÃO pode ser removido — o
        // lojista salvo precisa estar ACTIVE.
        ArgumentCaptor<Merchant> saved = ArgumentCaptor.forClass(Merchant.class);
        verify(configProvider).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(MerchantStatus.ACTIVE);
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.MERCHANT_ACTIVATED),
                eq(MERCHANT_ID), eq("MERCHANT"), eq(MERCHANT_ID), any(), any(), any(), eq(IP));
    }

    @Test
    void banRequiresStepUpAndAudits() {
        grant(MasterPermission.MERCHANT_BAN);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("0.00"), MerchantConfig.defaults())));

        service.ban(MERCHANT_ID, "Fraude confirmada", ACTOR_ID, IP);

        verify(stepUpGuard).requireSteppedUp(ACTOR, MasterPermission.MERCHANT_BAN);
        // L172 (VoidMethodCallMutator): ban NÃO pode ser removido — o lojista
        // salvo precisa estar BANNED.
        ArgumentCaptor<Merchant> saved = ArgumentCaptor.forClass(Merchant.class);
        verify(configProvider).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(MerchantStatus.BANNED);
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.MERCHANT_BANNED),
                eq(MERCHANT_ID), eq("MERCHANT"), eq(MERCHANT_ID), any(), any(), eq("Fraude confirmada"), eq(IP));
    }

    @Test
    void banAlreadyBannedThrowsInvalidState() {
        grant(MasterPermission.MERCHANT_BAN);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.BANNED, Money.of("0.00"), MerchantConfig.defaults())));

        assertThatThrownBy(() -> service.ban(MERCHANT_ID, "De novo", ACTOR_ID, IP))
                .isInstanceOf(IllegalStateException.class);
        verify(configProvider, never()).save(any(Merchant.class));
    }

    @Test
    void banWithoutReasonThrowsReasonRequired() {
        grant(MasterPermission.MERCHANT_BAN);
        when(configProvider.findWithConfigForUpdate(MERCHANT_ID))
                .thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE, Money.of("0.00"), MerchantConfig.defaults())));

        assertThatThrownBy(() -> service.ban(MERCHANT_ID, null, ACTOR_ID, IP))
                .isInstanceOf(AuditReasonRequiredException.class);
        verify(configProvider, never()).save(any(Merchant.class));
    }
}
