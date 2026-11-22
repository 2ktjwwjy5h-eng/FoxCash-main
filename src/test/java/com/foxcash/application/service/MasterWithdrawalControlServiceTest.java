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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterWithdrawalControlService} (Spec 14): o master
 * bloqueia/libera novos saques de um lojista persistindo a flag
 * {@code withdrawals_blocked}.
 */
class MasterWithdrawalControlServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String IP = "192.168.1.10";
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final MasterAuditRecorder auditRecorder = mock(MasterAuditRecorder.class);
    private final MasterPermissionGuard permissionGuard = mock(MasterPermissionGuard.class);

    private MasterWithdrawalControlService service;

    @BeforeEach
    void setUp() {
        service = new MasterWithdrawalControlService(merchantRepository, auditRecorder, permissionGuard);
    }

    private Merchant merchant(boolean blocked) {
        return Merchant.restore(MERCHANT_ID, "Loja Teste LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("100.00"), blocked, NOW);
    }

    @Test
    void blockPersistsMerchantWithFlagEnabled() {
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant(false)));

        service.block(MERCHANT_ID, ACTOR_ID, IP);

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(captor.capture());
        assertThat(captor.getValue().withdrawalsBlocked()).isTrue();

        // Mutation (EmptyObjectReturnVals L62): state() devolve o before/after da
        // auditoria (Spec 37) — deve conter withdrawalsBlocked (mutante: vazio).
        ArgumentCaptor<Map<String, Object>> beforeCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> afterCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.WITHDRAWALS_BLOCK),
                eq(MERCHANT_ID), eq("MERCHANT"), eq(MERCHANT_ID),
                beforeCaptor.capture(), afterCaptor.capture(), isNull(), eq(IP));
        assertThat(beforeCaptor.getValue()).containsEntry("withdrawalsBlocked", false);
        assertThat(afterCaptor.getValue()).containsEntry("withdrawalsBlocked", true);
    }

    @Test
    void releasePersistsMerchantWithFlagDisabled() {
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant(true)));

        service.release(MERCHANT_ID, ACTOR_ID, IP);

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(captor.capture());
        assertThat(captor.getValue().withdrawalsBlocked()).isFalse();
    }

    @Test
    void blockUnknownMerchantThrowsNotFound() {
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.block(MERCHANT_ID, ACTOR_ID, IP))
                .isInstanceOf(MerchantNotFoundException.class);

        verify(merchantRepository, never()).save(any());
    }

    // ---------- SEC-19 (revisão de segurança 2026-08-11): save sem lock não sobrescreve movimentação ----------

    @Test
    void blockUsesPessimisticLockAndPreservesConcurrentWalletBalance() {
        // SEC-19: o save grava TODOS os campos (incluindo wallet_balance). A
        // re-leitura com FOR UPDATE garante que o saldo salvo é o FRESCO — um
        // débito de saque concorrente (que também usa FOR UPDATE) não é
        // revertido por uma snapshot stale do block.
        Merchant fresh = Merchant.restore(MERCHANT_ID, "Loja Teste LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of("60.00"), false, NOW); // saldo pós-débito
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(fresh));

        service.block(MERCHANT_ID, ACTOR_ID, IP);

        ArgumentCaptor<Merchant> captor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(captor.capture());
        assertThat(captor.getValue().walletBalance().amount()).isEqualByComparingTo("60.00");
        assertThat(captor.getValue().withdrawalsBlocked()).isTrue();
    }
}
