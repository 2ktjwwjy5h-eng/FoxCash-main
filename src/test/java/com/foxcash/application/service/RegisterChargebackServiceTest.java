package com.foxcash.application.service;

import com.foxcash.domain.exception.ChargebackAlreadyExistsException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.fraud.Chargeback;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.out.ChargebackRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.dao.DataIntegrityViolationException;

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
 * Testes do registro de chargeback (Spec 19): valida transacao existente,
 * pertencente ao lojista, {@code APPROVED} e sem duplicidade; o estorno
 * financeiro debita o wallet do lojista (QA 2026-08-04).
 */
class RegisterChargebackServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final ChargebackRepository chargebackRepository = mock(ChargebackRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final WalletLedgerRecorder ledgerRecorder = mock(WalletLedgerRecorder.class);
    private final ReceivableRecorder receivableRecorder = mock(ReceivableRecorder.class);
    private final WebhookEventPublisher webhookEventPublisher = mock(WebhookEventPublisher.class);

    private RegisterChargebackService service;
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);

    @BeforeEach
    void setUp() {
        service = new RegisterChargebackService(transactionRepository, chargebackRepository,
                merchantRepository, ledgerRecorder, receivableRecorder, webhookEventPublisher,
                activityRecorder);
    }

    private PixTransaction transaction(UUID id) {
        return PixTransaction.restore(id, MERCHANT_ID, Money.of("150.00"), "Cobranca", null,
                "REF123", "REF123", null, null, TransactionStatus.APPROVED,
                Instant.now(), Instant.now(), null, "buyerhash");
    }

    private Merchant merchant() {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725", null,
                MerchantStatus.ACTIVE, new PricingPlan(Money.of("0.50"), Money.of("69.95")),
                Money.of("0.00"), false, Instant.now());
    }

    @Test
    void registersChargebackForValidTransactionAndDebitsWallet() {
        UUID txId = UUID.randomUUID();
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(transaction(txId)));
        when(chargebackRepository.findByTransactionId(txId)).thenReturn(Optional.empty());
        when(chargebackRepository.save(any(Chargeback.class))).thenAnswer(inv -> inv.getArgument(0));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant()));

        var result = service.register(MERCHANT_ID, txId, "Cliente reportou fraude");

        assertThat(result.chargebackId()).isNotNull();
        verify(chargebackRepository).save(any(Chargeback.class));

        // Fonte única de débito (Spec 29): o chargeback MARCA a transação REFUNDED
        // — o webhook REFUNDED posterior vira no-op e o débito acontece 1x.
        ArgumentCaptor<PixTransaction> txCaptor = ArgumentCaptor.forClass(PixTransaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().status()).isEqualTo(TransactionStatus.REFUNDED);

        // Estorno financeiro: valor aprovado sai do wallet (0 - 150 = -150).
        ArgumentCaptor<Merchant> merchantCaptor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(merchantCaptor.capture());
        assertThat(merchantCaptor.getValue().walletBalance().amount()).isEqualByComparingTo("-150.00");

        // Ledger (Spec 27): 1 entry CHARGEBACK_DEBIT com referência do chargeback.
        verify(ledgerRecorder).record(any(), eq(WalletLedgerEntryType.CHARGEBACK_DEBIT),
                eq(Money.of("150.00")), eq("CHARGEBACK"), any());

        // Mutation (VoidMethodCall L118): recebível de saldo negativo registrado
        // na MESMA transação (Spec 33) — saldo 0 - 150 = -150 → receivable.
        verify(receivableRecorder).recordFromNegativeBalance(any(),
                eq(ReceivableOrigin.CHARGEBACK), any(), any());
        // Mutation (VoidMethodCall L121): webhook de saída (Spec 35) na mesma transação.
        verify(webhookEventPublisher).publish(eq(MERCHANT_ID),
                eq(WebhookEventType.CHARGEBACK_REGISTERED), any());
    }

    @Test
    void unknownTransactionThrowsNotFound() {
        UUID txId = UUID.randomUUID();
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(MERCHANT_ID, txId, "x"))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void missingMerchantThrowsMerchantNotFound() {
        // Mutation (NullReturnVals L110, lambda do orElseThrow): lojista
        // inexistente → MerchantNotFoundException (não NPE do `throw null`).
        UUID txId = UUID.randomUUID();
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(transaction(txId)));
        when(chargebackRepository.findByTransactionId(txId)).thenReturn(Optional.empty());
        when(chargebackRepository.save(any(Chargeback.class))).thenAnswer(inv -> inv.getArgument(0));
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(MERCHANT_ID, txId, "x"))
                .isInstanceOf(MerchantNotFoundException.class);

        verify(merchantRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void transactionOfAnotherMerchantIsRejected() {
        UUID txId = UUID.randomUUID();
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(transaction(txId)));

        assertThatThrownBy(() -> service.register(UUID.randomUUID(), txId, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateChargebackIsRejected() {
        UUID txId = UUID.randomUUID();
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(transaction(txId)));
        when(chargebackRepository.findByTransactionId(txId)).thenReturn(Optional.of(mock(Chargeback.class)));

        assertThatThrownBy(() -> service.register(MERCHANT_ID, txId, "x"))
                .isInstanceOf(ChargebackAlreadyExistsException.class);

        // Sem estorno e sem marcar a transação — o débito nunca duplica.
        verify(transactionRepository, never()).save(any());
        verify(merchantRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void concurrentDuplicateFallsBackToConflictOnUniqueViolation() {
        // Backstop (Spec 29): corrida extrema na unique uq_chargebacks_transaction
        // → 409 chargeback_already_exists (nunca 500), sem segundo débito.
        UUID txId = UUID.randomUUID();
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(transaction(txId)));
        when(chargebackRepository.findByTransactionId(txId)).thenReturn(Optional.empty());
        when(chargebackRepository.save(any(Chargeback.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> service.register(MERCHANT_ID, txId, "x"))
                .isInstanceOf(ChargebackAlreadyExistsException.class);

        verify(merchantRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void chargebackOnNonApprovedTransactionIsRejected() {
        UUID txId = UUID.randomUUID();
        PixTransaction pending = PixTransaction.restore(txId, MERCHANT_ID, Money.of("150.00"), "Cobranca", null,
                "REF123", "REF123", null, null, TransactionStatus.PENDING,
                Instant.now(), Instant.now(), null, "buyerhash");
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.register(MERCHANT_ID, txId, "x"))
                .isInstanceOf(IllegalStateException.class);

        // Sem estorno (nunca houve crédito) e sem chargeback persistido.
        verify(merchantRepository, never()).save(any());
        verify(chargebackRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }
}
