package com.foxcash.application.service;

import com.foxcash.domain.exception.AutoWithdrawBelowThresholdException;
import com.foxcash.domain.exception.InsufficientBalanceException;
import com.foxcash.domain.exception.MerchantNotActiveException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.OutstandingDebtException;
import com.foxcash.domain.exception.PvsLimitExceededException;
import com.foxcash.domain.exception.WithdrawalsBlockedException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;
import com.foxcash.domain.model.withdrawal.WithdrawalStatus;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.ReceivableRepository;
import com.foxcash.domain.port.out.WithdrawalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
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
 * Testes da Fase A do saque ({@link WithdrawalProcessingPersister}, B-04): a
 * criação do saque {@code PROCESSING} SEM {@code external_reference} é atômica
 * com o débito e o ledger — incluindo as validações do vendedor (ACTIVE +
 * desbloqueio + saldo + inadimplência, sob lock) e do saque administrativo
 * (bloqueio lacrado: só saldo, + ADMIN_RECOVERY + auditoria).
 */
class WithdrawalProcessingPersisterTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID DESTINATION_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final String IP = "192.168.1.10";
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final ReceivableRepository receivableRepository = mock(ReceivableRepository.class);
    private final WithdrawalRepository withdrawalRepository = mock(WithdrawalRepository.class);
    private final WalletLedgerRecorder ledgerRecorder = mock(WalletLedgerRecorder.class);
    private final MasterLedgerRecorder masterLedgerRecorder = mock(MasterLedgerRecorder.class);
    private final MasterAuditRecorder auditRecorder = mock(MasterAuditRecorder.class);

    private WithdrawalProcessingPersister persister;

    @BeforeEach
    void setUp() {
        persister = new WithdrawalProcessingPersister(merchantRepository, receivableRepository,
                withdrawalRepository, ledgerRecorder, masterLedgerRecorder, auditRecorder);
        when(withdrawalRepository.save(any(Withdrawal.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Merchant merchant(MerchantStatus status, String balance, boolean blocked) {
        return Merchant.restore(MERCHANT_ID, "Loja Teste LTDA", null, "52998224725",
                null, status, PLAN, Money.of(balance), blocked, NOW);
    }

    private WithdrawalDestination destination(boolean active) {
        return new WithdrawalDestination(DESTINATION_ID, MERCHANT_ID, WithdrawalDestinationType.PIX_CPF,
                "52998224725", null, null, null, null, "Conta pessoal", active, NOW, NOW);
    }

    @Test
    void createMerchantWithdrawalDebitsAndPersistsProcessingWithoutExternalReference() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "100.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        WithdrawalProcessingPersister.MerchantWithdrawal result = persister
                .createMerchantWithdrawal(MERCHANT_ID, Money.of("40.00"), Money.of("2.50"), destination(true), true);

        // Débito aplicado e devolvido (Spec 88: valor + taxa 2,50).
        assertThat(result.walletBalance().amount()).isEqualByComparingTo("57.50");

        // Lojista persistido com o saldo reduzido.
        ArgumentCaptor<Merchant> merchantCaptor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(merchantCaptor.capture());
        assertThat(merchantCaptor.getValue().walletBalance().amount()).isEqualByComparingTo("57.50");

        // Saque PROCESSING persistido SEM external_reference (a referência vem na Fase B).
        Withdrawal saved = result.withdrawal();
        assertThat(saved.status()).isEqualTo(WithdrawalStatus.PROCESSING);
        assertThat(saved.externalReference()).isNull();
        assertThat(saved.payoutProvider()).isNull();
        assertThat(saved.destinationId()).isEqualTo(DESTINATION_ID);
        assertThat(saved.type()).isEqualTo(WithdrawalType.MERCHANT);
        assertThat(saved.destinationKeySnapshot()).isEqualTo("PIX_CPF:52998224725");

        // Ledger (Spec 27): WITHDRAWAL_DEBIT (valor) + WITHDRAWAL_FEE_DEBIT (taxa,
        // Spec 88) na mesma transação; a receita da taxa entra no ledger do master.
        assertThat(saved.fee().amount()).isEqualByComparingTo("2.50");
        verify(ledgerRecorder).record(any(), eq(WalletLedgerEntryType.WITHDRAWAL_DEBIT),
                eq(Money.of("40.00")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(ledgerRecorder).record(any(), eq(WalletLedgerEntryType.WITHDRAWAL_FEE_DEBIT),
                eq(Money.of("2.50")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(masterLedgerRecorder).record(MERCHANT_ID, MasterLedgerEntryType.WITHDRAWAL_FEE,
                Money.of("2.50"), "WITHDRAWAL", saved.id());
    }

    @Test
    void createMerchantWithdrawalWithInsufficientBalanceThrowsAndDoesNotPersist() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "100.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        assertThatThrownBy(() -> persister.createMerchantWithdrawal(
                MERCHANT_ID, Money.of("150.00"), Money.of("2.50"), destination(true), true))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(merchantRepository, never()).save(any());
        verify(withdrawalRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void createMerchantWithdrawalBlockedThrows() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "100.00", true);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        assertThatThrownBy(() -> persister.createMerchantWithdrawal(
                MERCHANT_ID, Money.of("10.00"), Money.of("2.50"), destination(true), true))
                .isInstanceOf(WithdrawalsBlockedException.class);

        verify(withdrawalRepository, never()).save(any());
    }

    @Test
    void createMerchantWithdrawalInactiveMerchantThrows() {
        Merchant merchant = merchant(MerchantStatus.SUSPENDED, "100.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        assertThatThrownBy(() -> persister.createMerchantWithdrawal(
                MERCHANT_ID, Money.of("10.00"), Money.of("2.50"), destination(true), true))
                .isInstanceOf(MerchantNotActiveException.class);

        verify(withdrawalRepository, never()).save(any());
    }

    @Test
    void createMerchantWithdrawalUnknownMerchantThrowsNotFound() {
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> persister.createMerchantWithdrawal(
                MERCHANT_ID, Money.of("10.00"), Money.of("2.50"), destination(true), true))
                .isInstanceOf(MerchantNotFoundException.class);

        verify(withdrawalRepository, never()).save(any());
    }

    @Test
    void createMerchantWithdrawalWithOutstandingDebtThrowsConflict() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "100.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(receivableRepository.hasOpenDebt(MERCHANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> persister.createMerchantWithdrawal(
                MERCHANT_ID, Money.of("10.00"), Money.of("2.50"), destination(true), true))
                .isInstanceOf(OutstandingDebtException.class);

        verify(withdrawalRepository, never()).save(any());
    }

    @Test
    void createMerchantWithdrawalDebtButBlockDisabledSucceeds() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "100.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(receivableRepository.hasOpenDebt(MERCHANT_ID)).thenReturn(true);

        WithdrawalProcessingPersister.MerchantWithdrawal result = persister
                .createMerchantWithdrawal(MERCHANT_ID, Money.of("40.00"), Money.of("2.50"), destination(true), false);

        assertThat(result.walletBalance().amount()).isEqualByComparingTo("57.50");
        verify(withdrawalRepository).save(any(Withdrawal.class));
    }

    @Test
    void createMerchantWithdrawalSequentialNeverOverdraft() {
        // Mesmo lojista (mesma instância do findByIdForUpdate) saca duas vezes — o
        // segundo acima do restante é 409 (lock serializado, Spec 26).
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "100.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        WithdrawalProcessingPersister.MerchantWithdrawal first =
                persister.createMerchantWithdrawal(MERCHANT_ID, Money.of("60.00"), Money.of("2.50"), destination(true), true);
        assertThat(first.walletBalance().amount()).isEqualByComparingTo("37.50");

        assertThatThrownBy(() -> persister.createMerchantWithdrawal(
                MERCHANT_ID, Money.of("50.00"), Money.of("2.50"), destination(true), true))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThat(merchant.walletBalance().amount()).isEqualByComparingTo("37.50");
    }

    @Test
    void createAdministrativeWithdrawalPassesEvenWithBlockedWithdrawals() {
        // Bloqueio ativo (vendedor bloqueado) NÃO impede o recolhimento do master.
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "100.00", true);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        WithdrawalProcessingPersister.AdminWithdrawal result = persister
                .createAdministrativeWithdrawal(MERCHANT_ID, Money.of("40.00"),
                        "52998224725", "Recolhimento de taxa", ACTOR_ID, IP);

        assertThat(result.walletBalance().amount()).isEqualByComparingTo("60.00");

        Withdrawal saved = result.withdrawal();
        assertThat(saved.type()).isEqualTo(WithdrawalType.ADMINISTRATIVE);
        assertThat(saved.status()).isEqualTo(WithdrawalStatus.PROCESSING);
        assertThat(saved.externalReference()).isNull(); // Fase B grava depois
        assertThat(saved.reason()).isEqualTo("Recolhimento de taxa");
        assertThat(saved.destinationId()).isNull();
        assertThat(saved.destinationKeySnapshot()).isEqualTo("ADMINISTRATIVE:52998224725");

        verify(ledgerRecorder).record(any(), eq(WalletLedgerEntryType.WITHDRAWAL_DEBIT),
                eq(Money.of("40.00")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(masterLedgerRecorder).record(MERCHANT_ID, MasterLedgerEntryType.ADMIN_RECOVERY,
                Money.of("40.00"), "WITHDRAWAL", saved.id());
        verify(auditRecorder).record(eq(ACTOR_ID), eq(MasterAuditAction.ADMINISTRATIVE_WITHDRAWAL),
                eq(MERCHANT_ID), eq("WITHDRAWAL"), eq(saved.id()), any(), any(), eq("Recolhimento de taxa"), eq(IP));
    }

    @Test
    void createAdministrativeWithdrawalOverBalanceThrowsAndNeverGoesNegative() {
        Merchant merchant = merchant(MerchantStatus.SUSPENDED, "100.00", true);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        assertThatThrownBy(() -> persister.createAdministrativeWithdrawal(
                MERCHANT_ID, Money.of("150.00"), "52998224725", "Recolhimento", ACTOR_ID, IP))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(merchant.walletBalance().amount()).isEqualByComparingTo("100.00"); // nunca negativo
        verify(withdrawalRepository, never()).save(any());
    }

    @Test
    void createAdministrativeWithdrawalUnknownMerchantThrowsNotFound() {
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> persister.createAdministrativeWithdrawal(
                MERCHANT_ID, Money.of("10.00"), "52998224725", "Recolhimento", ACTOR_ID, IP))
                .isInstanceOf(MerchantNotFoundException.class);

        verify(withdrawalRepository, never()).save(any());
    }

    // ---------- Spec 70: Fase A do AUTO-SAQUE (valor = saldo sob lock) ----------

    @Test
    void createMerchantAutoWithdrawalDebitsFullBalanceUnderLock() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "1234.56", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        WithdrawalProcessingPersister.MerchantWithdrawal result = persister
                .createMerchantAutoWithdrawal(MERCHANT_ID, destination(true),
                        Money.of("1000.00"), true, Money.of("2.50"));

        // Valor = TODO o saldo disponivel MENOS a taxa (Spec 88) — o auto-saque
        // paga a taxa do próprio saldo; wallet zera.
        assertThat(result.walletBalance().amount()).isEqualByComparingTo("0.00");

        Withdrawal saved = result.withdrawal();
        assertThat(saved.amount().amount()).isEqualByComparingTo("1232.06");
        assertThat(saved.fee().amount()).isEqualByComparingTo("2.50");
        assertThat(saved.status()).isEqualTo(WithdrawalStatus.PROCESSING);
        assertThat(saved.externalReference()).isNull(); // Fase B grava depois
        assertThat(saved.type()).isEqualTo(WithdrawalType.MERCHANT);
        assertThat(saved.destinationId()).isEqualTo(DESTINATION_ID);

        // Ledger (Spec 27): WITHDRAWAL_DEBIT (valor) + WITHDRAWAL_FEE_DEBIT (taxa).
        verify(ledgerRecorder).record(any(), eq(WalletLedgerEntryType.WITHDRAWAL_DEBIT),
                eq(Money.of("1232.06")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(ledgerRecorder).record(any(), eq(WalletLedgerEntryType.WITHDRAWAL_FEE_DEBIT),
                eq(Money.of("2.50")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(masterLedgerRecorder).record(MERCHANT_ID, MasterLedgerEntryType.WITHDRAWAL_FEE,
                Money.of("2.50"), "WITHDRAWAL", saved.id());
    }

    @Test
    void createMerchantAutoWithdrawalBelowThresholdThrowsAutoWithdrawException() {
        // Corrida com refund/estorno: saldo caiu abaixo do limiar na hora do lock
        // → AutoWithdrawBelowThresholdException (skip silencioso do job).
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "900.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        assertThatThrownBy(() -> persister.createMerchantAutoWithdrawal(
                MERCHANT_ID, destination(true), Money.of("1000.00"), true, Money.of("2.50")))
                .isInstanceOf(AutoWithdrawBelowThresholdException.class);

        verify(withdrawalRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void createMerchantAutoWithdrawalRespectsManualRules() {
        // Mesmas regras do saque manual (critério 6): bloqueado → skip, sem efeito.
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "2000.00", true);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        assertThatThrownBy(() -> persister.createMerchantAutoWithdrawal(
                MERCHANT_ID, destination(true), Money.of("1000.00"), true, Money.of("2.50")))
                .isInstanceOf(WithdrawalsBlockedException.class);
        verify(withdrawalRepository, never()).save(any());
    }

    @Test
    void createMerchantAutoWithdrawalWithDebtSkipsWhenBlockEnabled() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "2000.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(receivableRepository.hasOpenDebt(MERCHANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> persister.createMerchantAutoWithdrawal(
                MERCHANT_ID, destination(true), Money.of("1000.00"), true, Money.of("2.50")))
                .isInstanceOf(OutstandingDebtException.class);
        verify(withdrawalRepository, never()).save(any());
    }

    // ---------- Spec 120: Fase A do PAGAMENTO VIA SALDO (destino arbitrário) ----------

    @Test
    void createBillPaymentWithdrawalDebitsAmountPlusFeeAndPersistsProcessing() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "100.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(withdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(
                eq(MERCHANT_ID), eq(WithdrawalType.BILL_PAYMENT), any())).thenReturn(BigDecimal.ZERO);

        WithdrawalProcessingPersister.MerchantWithdrawal result = persister
                .createBillPaymentWithdrawal(MERCHANT_ID, Money.of("40.00"), Money.of("2.50"),
                        "52998224725", new BigDecimal("5000.00"), new BigDecimal("1000.00"), true);

        // Débito aplicado (valor + taxa, Spec 88).
        assertThat(result.walletBalance().amount()).isEqualByComparingTo("57.50");

        // Saque PROCESSING com destino ARBITRÁRIO: type BILL_PAYMENT, destination_id
        // null e a chave extraída do brcode no snapshot (criptografada em repouso).
        Withdrawal saved = result.withdrawal();
        assertThat(saved.status()).isEqualTo(WithdrawalStatus.PROCESSING);
        assertThat(saved.type()).isEqualTo(WithdrawalType.BILL_PAYMENT);
        assertThat(saved.destinationId()).isNull();
        assertThat(saved.destinationKeySnapshot()).isEqualTo("52998224725");
        assertThat(saved.externalReference()).isNull();
        assertThat(saved.fee().amount()).isEqualByComparingTo("2.50");

        // Ledger: WITHDRAWAL_DEBIT + WITHDRAWAL_FEE_DEBIT (wallet) + WITHDRAWAL_FEE (master).
        verify(ledgerRecorder).record(any(), eq(WalletLedgerEntryType.WITHDRAWAL_DEBIT),
                eq(Money.of("40.00")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(ledgerRecorder).record(any(), eq(WalletLedgerEntryType.WITHDRAWAL_FEE_DEBIT),
                eq(Money.of("2.50")), eq("WITHDRAWAL"), eq(saved.id()));
        verify(masterLedgerRecorder).record(MERCHANT_ID, MasterLedgerEntryType.WITHDRAWAL_FEE,
                Money.of("2.50"), "WITHDRAWAL", saved.id());
    }

    @Test
    void createBillPaymentWithdrawalOverMaxPerPaymentThrows() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "10000.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));

        // amount 1000 + fee 2,50 = 1002,50 > teto por operação 1000,00.
        assertThatThrownBy(() -> persister.createBillPaymentWithdrawal(
                MERCHANT_ID, Money.of("1000.00"), Money.of("2.50"),
                "52998224725", new BigDecimal("5000.00"), new BigDecimal("1000.00"), true))
                .isInstanceOf(PvsLimitExceededException.class)
                .satisfies(e -> assertThat(((PvsLimitExceededException) e).code())
                        .isEqualTo("pvs_max_per_payment_exceeded"));

        verify(merchantRepository, never()).save(any());
        verify(withdrawalRepository, never()).save(any());
        verify(ledgerRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void createBillPaymentWithdrawalOverDailyCapThrows() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "10000.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        // Já usados hoje 4.900,00; novo 100,00 + 2,50 = 5.002,50 > teto diário 5.000,00.
        when(withdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(
                eq(MERCHANT_ID), eq(WithdrawalType.BILL_PAYMENT), any()))
                .thenReturn(new BigDecimal("4900.00"));

        assertThatThrownBy(() -> persister.createBillPaymentWithdrawal(
                MERCHANT_ID, Money.of("100.00"), Money.of("2.50"),
                "52998224725", new BigDecimal("5000.00"), new BigDecimal("1000.00"), true))
                .isInstanceOf(PvsLimitExceededException.class)
                .satisfies(e -> assertThat(((PvsLimitExceededException) e).code())
                        .isEqualTo("pvs_daily_cap_exceeded"));

        verify(merchantRepository, never()).save(any());
        verify(withdrawalRepository, never()).save(any());
    }

    @Test
    void createBillPaymentWithdrawalCountsAllStatusesForDailyCap() {
        // A consulta do teto diário é chamada ANTES do save — TODAS as tentativas
        // do dia contabilizam (qualquer status), com o since = início do dia UTC.
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "100.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(withdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(
                eq(MERCHANT_ID), eq(WithdrawalType.BILL_PAYMENT), any())).thenReturn(BigDecimal.ZERO);

        persister.createBillPaymentWithdrawal(MERCHANT_ID, Money.of("40.00"), Money.of("2.50"),
                "52998224725", new BigDecimal("5000.00"), new BigDecimal("1000.00"), true);

        verify(withdrawalRepository).sumAmountAndFeeByMerchantAndTypeCreatedSince(
                eq(MERCHANT_ID), eq(WithdrawalType.BILL_PAYMENT), any(Instant.class));
    }

    @Test
    void createBillPaymentWithdrawalInsufficientBalanceThrows() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE, "40.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(merchant));
        when(withdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(
                eq(MERCHANT_ID), eq(WithdrawalType.BILL_PAYMENT), any())).thenReturn(BigDecimal.ZERO);

        // Saldo 40,00 não cobre 40,00 + 2,50 de taxa → 409 insufficient_balance.
        assertThatThrownBy(() -> persister.createBillPaymentWithdrawal(
                MERCHANT_ID, Money.of("40.00"), Money.of("2.50"),
                "52998224725", new BigDecimal("5000.00"), new BigDecimal("1000.00"), true))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(merchantRepository, never()).save(any());
        verify(withdrawalRepository, never()).save(any());
    }

    @Test
    void createBillPaymentWithdrawalRespectsBlockedAndDebt() {
        // Mesmos gates do saque do vendedor (reuso): bloqueio → 409, dívida → 409.
        // A consulta do teto diário roda antes do withdraw (Fase A) — stub padrão.
        Merchant blocked = merchant(MerchantStatus.ACTIVE, "1000.00", true);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(blocked));
        when(withdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(
                eq(MERCHANT_ID), eq(WithdrawalType.BILL_PAYMENT), any())).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> persister.createBillPaymentWithdrawal(
                MERCHANT_ID, Money.of("10.00"), Money.of("2.50"),
                "52998224725", new BigDecimal("5000.00"), new BigDecimal("1000.00"), true))
                .isInstanceOf(WithdrawalsBlockedException.class);

        Merchant inDebt = merchant(MerchantStatus.ACTIVE, "1000.00", false);
        when(merchantRepository.findByIdForUpdate(MERCHANT_ID)).thenReturn(Optional.of(inDebt));
        when(receivableRepository.hasOpenDebt(MERCHANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> persister.createBillPaymentWithdrawal(
                MERCHANT_ID, Money.of("10.00"), Money.of("2.50"),
                "52998224725", new BigDecimal("5000.00"), new BigDecimal("1000.00"), true))
                .isInstanceOf(OutstandingDebtException.class);

        verify(withdrawalRepository, never()).save(any());
    }
}
