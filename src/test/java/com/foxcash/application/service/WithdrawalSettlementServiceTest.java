package com.foxcash.application.service;

import com.foxcash.domain.exception.WithdrawalNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalStatus;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.WithdrawalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * Testes do {@link WithdrawalSettlementService} (Spec 36 — webhook de saida):
 * PAID → COMPLETED (publica WITHDRAWAL_COMPLETED); FAILED → estorno unico
 * (credit + ledger WITHDRAWAL_REVERSAL); re-entrega → no-op; externalReference
 * desconhecido → 404.
 */
class WithdrawalSettlementServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final WithdrawalRepository withdrawalRepository = mock(WithdrawalRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final WalletLedgerRecorder ledgerRecorder = mock(WalletLedgerRecorder.class);
    private final MasterLedgerRecorder masterLedgerRecorder = mock(MasterLedgerRecorder.class);
    private final WebhookEventPublisher webhookEventPublisher = mock(WebhookEventPublisher.class);

    private WithdrawalSettlementService service;
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);

    @BeforeEach
    void setUp() {
        service = new WithdrawalSettlementService(withdrawalRepository, merchantRepository,
                ledgerRecorder, masterLedgerRecorder, webhookEventPublisher, activityRecorder);
    }

    private Withdrawal processing(String externalReference) {
        // Spec 88: saque do vendedor paga taxa (2,50).
        return Withdrawal.processing(MERCHANT_ID, Money.of("50.00"), Money.of("2.50"), null,
                "PIX_CPF:52998224725", WithdrawalType.MERCHANT, null)
                .submitToPsp(externalReference, "MOCK");
    }

    private Withdrawal processingAdministrative(String externalReference) {
        // Spec 88: saque administrativo é ISENTO de taxa (0.00).
        return Withdrawal.processing(MERCHANT_ID, Money.of("50.00"), Money.of("0.00"), null,
                "ADMINISTRATIVE:chave", WithdrawalType.ADMINISTRATIVE, "Recolhimento")
                .submitToPsp(externalReference, "MOCK");
    }

    private Merchant merchant(String balance) {
        return Merchant.restore(MERCHANT_ID, "Loja LTDA", null, "52998224725",
                null, MerchantStatus.ACTIVE, PLAN, Money.of(balance), false, NOW);
    }

    @Test
    void paidCompletesWithdrawalAndPublishesEvent() {
        when(withdrawalRepository.findByExternalReferenceForUpdate("PO1"))
                .thenReturn(Optional.of(processing("PO1")));
        when(withdrawalRepository.save(any(Withdrawal.class))).thenAnswer(inv -> inv.getArgument(0));

        service.settleByExternalReference("PO1", "PAID", null);

        ArgumentCaptor<Withdrawal> captor = ArgumentCaptor.forClass(Withdrawal.class);
        verify(withdrawalRepository).save(captor.capture());
        Withdrawal saved = captor.getValue();
        assertThat(saved.status()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(saved.completedAt()).isNotNull();
        assertThat(saved.failedAt()).isNull();

        verify(webhookEventPublisher).publish(eq(MERCHANT_ID),
                eq(WebhookEventType.WITHDRAWAL_COMPLETED), any());
        verify(merchantRepository, never()).findByIdForUpdate(any());
        verify(ledgerRecorder, never()).record(any(), eq(WalletLedgerEntryType.WITHDRAWAL_REVERSAL),
                any(), any(), any());
    }

    @Test
    void failedRefundsBalanceAndRecordsReversal() {
        when(withdrawalRepository.findByExternalReferenceForUpdate("PO1"))
                .thenReturn(Optional.of(processing("PO1")));
        when(withdrawalRepository.save(any(Withdrawal.class))).thenAnswer(inv -> inv.getArgument(0));
        Merchant merchant = merchant("100.00");
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        service.settleByExternalReference("PO1", "FAILED", "Saldo insuficiente no PSP");

        ArgumentCaptor<Withdrawal> captor = ArgumentCaptor.forClass(Withdrawal.class);
        verify(withdrawalRepository).save(captor.capture());
        Withdrawal saved = captor.getValue();
        assertThat(saved.status()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(saved.failureReason()).isEqualTo("Saldo insuficiente no PSP");
        assertThat(saved.failedAt()).isNotNull();

        // Estorno: saldo 100 + 50 + 2,50 (taxa devolvida — Spec 88) = 152,50 +
        // ledger WITHDRAWAL_REVERSAL + WITHDRAWAL_FEE_REVERSAL na mesma transação.
        assertThat(merchant.walletBalance().amount()).isEqualByComparingTo("152.50");
        verify(merchantRepository).save(merchant);
        verify(ledgerRecorder).record(eq(merchant), eq(WalletLedgerEntryType.WITHDRAWAL_REVERSAL),
                eq(Money.of("50.00")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(ledgerRecorder).record(eq(merchant), eq(WalletLedgerEntryType.WITHDRAWAL_FEE_REVERSAL),
                eq(Money.of("2.50")), eq("WITHDRAWAL"), eq(saved.id()));
        // Spec 88 (padrão SEC-29): a receita da taxa registrada na Fase A é revertida.
        verify(masterLedgerRecorder).record(eq(MERCHANT_ID),
                eq(MasterLedgerEntryType.WITHDRAWAL_FEE_REVERSAL),
                eq(Money.of("2.50")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(webhookEventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void reDeliveryAfterSettlementIsNoOp() {
        // Saque ja COMPLETED (ou FAILED): re-entrega do evento nao duplica estorno.
        when(withdrawalRepository.findByExternalReferenceForUpdate("PO1"))
                .thenReturn(Optional.of(processing("PO1").complete(NOW)));

        service.settleByExternalReference("PO1", "FAILED", "tarde demais");

        verify(withdrawalRepository, never()).save(any());
        verify(merchantRepository, never()).findByIdForUpdate(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
        verify(webhookEventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void settleFailedByWithdrawalIdLocksWithForUpdateAndReverses() {
        // SEC-22: o estorno de falha síncrona (createPayout lançou) usa
        // findByIdForUpdate — mesma serialização do webhook/reconciliação.
        // Antes, o findById sem lock deixava o gate de status operar sobre
        // snapshot stale e um settlement concorrente podia ser sobrescrito
        // (estorno + COMPLETED ao mesmo tempo = efeito financeiro duplicado).
        Withdrawal processing = processing("PO1"); // sem external_reference (falhou antes do submit)
        when(withdrawalRepository.findByIdForUpdate(processing.id())).thenReturn(Optional.of(processing));
        when(withdrawalRepository.save(any(Withdrawal.class))).thenAnswer(inv -> inv.getArgument(0));
        Merchant merchant = merchant("100.00");
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        service.settleFailedByWithdrawalId(processing.id(), "Falha no PSP: timeout");

        ArgumentCaptor<Withdrawal> captor = ArgumentCaptor.forClass(Withdrawal.class);
        verify(withdrawalRepository).save(captor.capture());
        Withdrawal saved = captor.getValue();
        assertThat(saved.status()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(saved.failureReason()).isEqualTo("Falha no PSP: timeout");

        // Estorno do débito + taxa devolvida + ledger na mesma transação (saldo
        // 100 + 50 + 2,50 = 152,50 — Spec 88).
        assertThat(merchant.walletBalance().amount()).isEqualByComparingTo("152.50");
        verify(ledgerRecorder).record(eq(merchant), eq(WalletLedgerEntryType.WITHDRAWAL_REVERSAL),
                eq(Money.of("50.00")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(ledgerRecorder).record(eq(merchant), eq(WalletLedgerEntryType.WITHDRAWAL_FEE_REVERSAL),
                eq(Money.of("2.50")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(webhookEventPublisher, never()).publish(any(), any(), any());
    }

    @Test
    void unknownExternalReferenceThrowsNotFound() {
        when(withdrawalRepository.findByExternalReferenceForUpdate("PO1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settleByExternalReference("PO1", "PAID", null))
                .isInstanceOf(WithdrawalNotFoundException.class);
    }

    // ---------- SEC-28 (revisão de segurança 2026-08-11): motivo longo do PSP truncado ----------

    @Test
    void failedReasonIsTruncatedToColumnLimit() {
        // SEC-28: o caller monta "Falha no PSP: " + e.getMessage() — uma mensagem
        // longa do provedor (Pagar.me devolve JSON de erro grande) estouraria a
        // coluna failure_reason (VARCHAR(255)) e a DataException DENTRO do
        // estorno deixaria o saque PROCESSING com débito pendurado (reconciliação
        // em loop). O domínio trunca a 255.
        Withdrawal processing = processing("PO1");
        when(withdrawalRepository.findByIdForUpdate(processing.id())).thenReturn(Optional.of(processing));
        when(withdrawalRepository.save(any(Withdrawal.class))).thenAnswer(inv -> inv.getArgument(0));
        Merchant merchant = merchant("100.00");
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        String longReason = "Falha no PSP: " + "x".repeat(500);
        service.settleFailedByWithdrawalId(processing.id(), longReason);

        ArgumentCaptor<Withdrawal> captor = ArgumentCaptor.forClass(Withdrawal.class);
        verify(withdrawalRepository).save(captor.capture());
        // Truncado no teto da coluna (255) — o estorno NÃO quebra.
        assertThat(captor.getValue().failureReason()).hasSize(255);
        assertThat(captor.getValue().status()).isEqualTo(WithdrawalStatus.FAILED);
        verify(ledgerRecorder).record(eq(merchant), eq(WalletLedgerEntryType.WITHDRAWAL_REVERSAL),
                eq(Money.of("50.00")), eq("WITHDRAWAL"), eq(captor.getValue().id()));
    }

    // ---------- SEC-29 (revisão de segurança 2026-08-11): reversão do ADMIN_RECOVERY no estorno ----------

    @Test
    void failedAdministrativeWithdrawalReversesAdminRecoveryOnMasterLedger() {
        // SEC-29: o saque ADMINISTRATIVO (Spec 32) registrou ADMIN_RECOVERY no
        // ledger do master na Fase A — quando o estorno devolve o valor ao
        // lojista, a receita registrada precisa ser revertida (senão o relatório
        // da Spec 34 mostra valor recolhido que não ocorreu).
        Withdrawal processing = processingAdministrative("PO1");
        when(withdrawalRepository.findByExternalReferenceForUpdate("PO1"))
                .thenReturn(Optional.of(processing));
        when(withdrawalRepository.save(any(Withdrawal.class))).thenAnswer(inv -> inv.getArgument(0));
        Merchant merchant = merchant("100.00");
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        service.settleByExternalReference("PO1", "FAILED", "Saldo insuficiente no PSP");

        ArgumentCaptor<Withdrawal> captor = ArgumentCaptor.forClass(Withdrawal.class);
        verify(withdrawalRepository).save(captor.capture());
        // Estorno do débito do lojista (mesmo fluxo)…
        assertThat(merchant.walletBalance().amount()).isEqualByComparingTo("150.00");
        // …E reversão do ADMIN_RECOVERY no ledger do master, mesma transação.
        verify(masterLedgerRecorder).record(eq(MERCHANT_ID),
                eq(MasterLedgerEntryType.ADMIN_RECOVERY_REVERSAL),
                eq(Money.of("50.00")), eq("WITHDRAWAL"), eq(captor.getValue().id()));
    }

    @Test
    void failedMerchantWithdrawalReversesOnlyWithdrawalFeeOnMasterLedger() {
        // Spec 88 + SEC-29: saque do VENDEDOR registrou WITHDRAWAL_FEE na Fase A —
        // o estorno reverte a TAXA no ledger do master (mas NÃO grava
        // ADMIN_RECOVERY_REVERSAL, que é só do saque administrativo).
        when(withdrawalRepository.findByExternalReferenceForUpdate("PO1"))
                .thenReturn(Optional.of(processing("PO1")));
        when(withdrawalRepository.save(any(Withdrawal.class))).thenAnswer(inv -> inv.getArgument(0));
        Merchant merchant = merchant("100.00");
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        service.settleByExternalReference("PO1", "FAILED", "Saldo insuficiente no PSP");

        verify(masterLedgerRecorder).record(eq(MERCHANT_ID),
                eq(MasterLedgerEntryType.WITHDRAWAL_FEE_REVERSAL),
                eq(Money.of("2.50")), eq("WITHDRAWAL"), any());
        verify(masterLedgerRecorder, never()).record(eq(MERCHANT_ID),
                eq(MasterLedgerEntryType.ADMIN_RECOVERY_REVERSAL), any(), any(), any());
    }
}
