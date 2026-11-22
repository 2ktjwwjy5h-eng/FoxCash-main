package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.out.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * Testes do {@link MerchantStatusControlService} (Spec 16, ADR-008): o master
 * suspende (SUSPENDED) e libera (ACTIVE) lojistas — decisao do operador. Cada
 * acao gera trilha de auditoria {@code RISK_ADJUSTMENT} (Spec 37).
 */
class MerchantStatusControlServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String IP = "192.168.1.10";
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final MasterAuditRecorder auditRecorder = mock(MasterAuditRecorder.class);
    private final MasterPermissionGuard permissionGuard = mock(MasterPermissionGuard.class);

    private MerchantStatusControlService service;

    @BeforeEach
    void setUp() {
        service = new MerchantStatusControlService(merchantRepository, auditRecorder, permissionGuard);
    }

    private Merchant merchant(MerchantStatus status) {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                null, status, PLAN, Money.of("0.00"), false, NOW);
    }

    @Test
    void suspendPersistsMerchantAsSuspendedAndAudits() {
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));

        service.suspend(MERCHANT_ID, ACTOR_ID, IP);

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(MerchantStatus.SUSPENDED);
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.RISK_ADJUSTMENT),
                eq(MERCHANT_ID), eq("MERCHANT"), eq(MERCHANT_ID),
                eq(Map.of("status", "ACTIVE")), eq(Map.of("status", "SUSPENDED")), eq(null), eq(IP));
    }

    @Test
    void releasePersistsMerchantAsActiveAndAudits() {
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.SUSPENDED)));

        service.release(MERCHANT_ID, ACTOR_ID, IP);

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(MerchantStatus.ACTIVE);
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.RISK_ADJUSTMENT),
                eq(MERCHANT_ID), eq("MERCHANT"), eq(MERCHANT_ID),
                eq(Map.of("status", "SUSPENDED")), eq(Map.of("status", "ACTIVE")), eq(null), eq(IP));
    }

    @Test
    void suspendUnknownMerchantThrowsNotFound() {
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.suspend(MERCHANT_ID, ACTOR_ID, IP))
                .isInstanceOf(MerchantNotFoundException.class);

        verify(merchantRepository, never()).save(any());
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---------- SEC-19 (revisão de segurança 2026-08-11): save sem lock não sobrescreve movimentação ----------

    @Test
    void suspendUsesPessimisticLockAndPreservesConcurrentWalletBalance() {
        // SEC-19: o save do merchant grava TODOS os campos (incluindo
        // wallet_balance). A re-leitura com FOR UPDATE garante que o estado
        // salvo é o FRESCO — um crédito/débito concorrente (que também usa FOR
        // UPDATE) não é revertido por uma snapshot stale do suspend.
        Merchant fresh = Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("250.00"), false, NOW);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(fresh));

        service.suspend(MERCHANT_ID, ACTOR_ID, IP);

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(captor.capture());
        // O saldo FRESCO (250.00) é preservado no save — não o valor stale.
        assertThat(captor.getValue().walletBalance().amount()).isEqualByComparingTo("250.00");
        assertThat(captor.getValue().status()).isEqualTo(MerchantStatus.SUSPENDED);
    }
}
