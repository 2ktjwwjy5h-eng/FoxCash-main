package com.foxcash.application.service;

import com.foxcash.domain.exception.AutoWithdrawBelowThresholdException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.PvsLimitExceededException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.ReceivableRepository;
import com.foxcash.domain.port.out.WithdrawalRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Fase A do saque (auditoria 2026-08-05/B-04): cria o saque {@code PROCESSING}
 * **sem** {@code external_reference}, debitando o wallet e registrando o ledger
 * — tudo em UMA transação (atomicidade do movimento financeiro preservada). O
 * PSP é chamado DEPOIS, fora da transação, pelo {@link WithdrawalService}/
 * {@link MasterWithdrawalService} (padrão da cobrança, Spec 28/M1a) — a conexão
 * do pool não fica retida durante o I/O de rede do provedor.
 *
 * <p>Fluxo do vendedor ({@link #createMerchantWithdrawal}) valida ACTIVE +
 * desbloqueio + saldo + inadimplência sob o lock pessimista do lojista; o fluxo
 * administrativo ({@link #createAdministrativeWithdrawal}) valida apenas saldo
 * (bloqueio lacrado, Spec 32) e registra também o {@code ADMIN_RECOVERY} no
 * ledger do master + a trilha de auditoria (Spec 37) na mesma transação.
 */
@Component
public class WithdrawalProcessingPersister {

    private final MerchantRepository merchantRepository;
    private final ReceivableRepository receivableRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final WalletLedgerRecorder ledgerRecorder;
    private final MasterLedgerRecorder masterLedgerRecorder;
    private final MasterAuditRecorder auditRecorder;

    public WithdrawalProcessingPersister(MerchantRepository merchantRepository,
                                         ReceivableRepository receivableRepository,
                                         WithdrawalRepository withdrawalRepository,
                                         WalletLedgerRecorder ledgerRecorder,
                                         MasterLedgerRecorder masterLedgerRecorder,
                                         MasterAuditRecorder auditRecorder) {
        this.merchantRepository = merchantRepository;
        this.receivableRepository = receivableRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.ledgerRecorder = ledgerRecorder;
        this.masterLedgerRecorder = masterLedgerRecorder;
        this.auditRecorder = auditRecorder;
    }

    /**
     * Fase A do saque do VENDEDOR: lock pessimista no lojista serializa saques
     * concorrentes — débito + PROCESSING (sem external_reference) + ledger
     * {@code WITHDRAWAL_DEBIT} na mesma transação.
     *
     * @param blockOnDebt configuração {@code foxcash.billing.block-withdrawals-on-debt}
     */
    @Transactional
    public MerchantWithdrawal createMerchantWithdrawal(UUID merchantId, Money amount,
                                                       Money fee,
                                                       WithdrawalDestination destination,
                                                       boolean blockOnDebt) {
        Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        // Inadimplencia (Spec 33): lojista com receivable OPEN/PARTIAL nao saca.
        merchant.markOutstandingDebt(receivableRepository.hasOpenDebt(merchantId));
        merchant.ensureNoOutstandingDebt(blockOnDebt);
        // Spec 88: o saldo precisa cobrir valor + taxa (validação do Merchant).
        merchant.withdraw(amount.add(fee));
        merchantRepository.save(merchant);

        // Spec 36: nasce PROCESSING SEM external_reference (a referência é gravada
        // na Fase B, em transação própria — B-04).
        Withdrawal withdrawal = Withdrawal.processing(merchantId, amount, fee,
                destination.id(), destination.snapshotValue(), WithdrawalType.MERCHANT, null);
        Withdrawal saved = withdrawalRepository.save(withdrawal);
        // Ledger (Spec 27): WITHDRAWAL_DEBIT (valor) + WITHDRAWAL_FEE_DEBIT (taxa,
        // Spec 88) na mesma transação; a receita da taxa entra no ledger do master.
        ledgerRecorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_DEBIT,
                saved.amount(), "WITHDRAWAL", saved.id());
        ledgerRecorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_FEE_DEBIT,
                saved.fee(), "WITHDRAWAL", saved.id());
        masterLedgerRecorder.record(merchantId, MasterLedgerEntryType.WITHDRAWAL_FEE,
                saved.fee(), "WITHDRAWAL", saved.id());
        return new MerchantWithdrawal(saved, merchant.walletBalance());
    }

    /**
     * Fase A do AUTO-SAQUE (Spec 70): mesmo fluxo do {@link #createMerchantWithdrawal},
     * com o valor **igual a todo o saldo disponivel** e o limiar avaliado DENTRO
     * do lock pessimista — sem corrida com credito/refund concorrente (D11.2).
     * Saldo abaixo do limiar na hora do lock →
     * {@link AutoWithdrawBelowThresholdException} (skip silencioso do job).
     *
     * @param threshold   limiar da config ({@code wallet_balance >= threshold})
     * @param blockOnDebt configuração {@code foxcash.billing.block-withdrawals-on-debt}
     */
    @Transactional
    public MerchantWithdrawal createMerchantAutoWithdrawal(UUID merchantId,
                                                           WithdrawalDestination destination,
                                                           Money threshold, boolean blockOnDebt,
                                                           Money fee) {
        Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        merchant.markOutstandingDebt(receivableRepository.hasOpenDebt(merchantId));
        merchant.ensureNoOutstandingDebt(blockOnDebt);
        if (merchant.walletBalance().amount().compareTo(threshold.amount()) < 0) {
            throw new AutoWithdrawBelowThresholdException(merchantId,
                    merchant.walletBalance(), threshold);
        }
        // Spec 88: o auto-saque paga a taxa do próprio saldo — saca saldo − taxa
        // (wallet zera). Saldo que não cobre a taxa → skip silencioso (mesma
        // semântica do limiar).
        Money amount = merchant.walletBalance().subtract(fee);
        if (amount.amount().signum() <= 0) {
            throw new AutoWithdrawBelowThresholdException(merchantId,
                    merchant.walletBalance(), fee);
        }
        // ACTIVE + desbloqueio + saldo (mesmas regras do manual).
        merchant.withdraw(amount.add(fee));
        merchantRepository.save(merchant);

        Withdrawal withdrawal = Withdrawal.processing(merchantId, amount, fee,
                destination.id(), destination.snapshotValue(), WithdrawalType.MERCHANT, null);
        Withdrawal saved = withdrawalRepository.save(withdrawal);
        ledgerRecorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_DEBIT,
                saved.amount(), "WITHDRAWAL", saved.id());
        ledgerRecorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_FEE_DEBIT,
                saved.fee(), "WITHDRAWAL", saved.id());
        masterLedgerRecorder.record(merchantId, MasterLedgerEntryType.WITHDRAWAL_FEE,
                saved.fee(), "WITHDRAWAL", saved.id());
        return new MerchantWithdrawal(saved, merchant.walletBalance());
    }

    /**
     * Fase A do PAGAMENTO VIA SALDO (Spec 120): mesmo fluxo do
     * {@link #createMerchantWithdrawal} com destino ARBITRÁRIO — a chave Pix é
     * extraída do Pix Copia e Cola estático pelo {@code PixCodeParser} e vai
     * para o {@code destinationKeySnapshot} ({@code destination_id = null}).
     * Debita valor + taxa (Spec 88), avalia os TETOS anti-abuso (por operação e
     * diário) DENTRO do lock pessimista do lojista (SEC-19 — sem corrida entre
     * PVS concorrentes: dois PVS somando acima do cap → exatamente um passa) e
     * registra o ledger (WITHDRAWAL_DEBIT + WITHDRAWAL_FEE_DEBIT + WITHDRAWAL_FEE).
     *
     * @param pixKey        chave Pix extraída do brcode (destino arbitrário)
     * @param dailyCap      teto diário ({@code foxcash.pvs.daily-cap})
     * @param maxPerPayment teto por operação ({@code foxcash.pvs.max-per-payment})
     * @param blockOnDebt   configuração {@code foxcash.billing.block-withdrawals-on-debt}
     */
    @Transactional
    public MerchantWithdrawal createBillPaymentWithdrawal(UUID merchantId, Money amount,
                                                          Money fee, String pixKey,
                                                          BigDecimal dailyCap, BigDecimal maxPerPayment,
                                                          boolean blockOnDebt) {
        Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        merchant.markOutstandingDebt(receivableRepository.hasOpenDebt(merchantId));
        merchant.ensureNoOutstandingDebt(blockOnDebt);
        // Anti-abuso (Spec 120): teto por operação (valor + taxa) e teto diário
        // (soma de amount+fee de TODOS os BILL_PAYMENT criados hoje — tentativa
        // contabiliza, qualquer status) avaliados sob o lock — sem corrida.
        Money total = amount.add(fee);
        if (total.amount().compareTo(maxPerPayment) > 0) {
            throw new PvsLimitExceededException("pvs_max_per_payment_exceeded",
                    "Valor do pagamento excede o teto por operação");
        }
        Instant since = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal used = withdrawalRepository.sumAmountAndFeeByMerchantAndTypeCreatedSince(
                merchantId, WithdrawalType.BILL_PAYMENT, since);
        if (used.add(total.amount()).compareTo(dailyCap) > 0) {
            throw new PvsLimitExceededException("pvs_daily_cap_exceeded",
                    "Teto diário de pagamentos via saldo excedido");
        }
        // ACTIVE + desbloqueio + saldo (mesmas regras do saque do vendedor).
        merchant.withdraw(total);
        merchantRepository.save(merchant);

        Withdrawal withdrawal = Withdrawal.processing(merchantId, amount, fee,
                null, pixKey, WithdrawalType.BILL_PAYMENT, null);
        Withdrawal saved = withdrawalRepository.save(withdrawal);
        ledgerRecorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_DEBIT,
                saved.amount(), "WITHDRAWAL", saved.id());
        ledgerRecorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_FEE_DEBIT,
                saved.fee(), "WITHDRAWAL", saved.id());
        masterLedgerRecorder.record(merchantId, MasterLedgerEntryType.WITHDRAWAL_FEE,
                saved.fee(), "WITHDRAWAL", saved.id());
        return new MerchantWithdrawal(saved, merchant.walletBalance());
    }

    /**
     * Fase A do saque ADMINISTRATIVO do master (Spec 32, bloqueio lacrado): passa
     * mesmo com bloqueio ativo/lojista não-ACTIVE, valida apenas saldo, e registra
     * {@code WITHDRAWAL_DEBIT} (ledger do lojista) + {@code ADMIN_RECOVERY} (ledger
     * do master, Spec 34) + trilha {@code ADMINISTRATIVE_WITHDRAWAL} (Spec 37) na
     * mesma transação.
     */
    @Transactional
    public AdminWithdrawal createAdministrativeWithdrawal(UUID merchantId, Money amount,
                                                          String destinationKey, String reason,
                                                          UUID actorId, String ip) {
        Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        Money balanceBefore = merchant.walletBalance();
        merchant.adminWithdraw(amount);
        merchantRepository.save(merchant);

        Withdrawal withdrawal = Withdrawal.processing(merchantId, amount, Money.of("0.00"),
                null, "ADMINISTRATIVE:" + destinationKey, WithdrawalType.ADMINISTRATIVE, reason);
        Withdrawal saved = withdrawalRepository.save(withdrawal);
        ledgerRecorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_DEBIT,
                amount, "WITHDRAWAL", saved.id());
        masterLedgerRecorder.record(merchantId, MasterLedgerEntryType.ADMIN_RECOVERY,
                amount, "WITHDRAWAL", saved.id());
        auditRecorder.record(actorId, MasterAuditAction.ADMINISTRATIVE_WITHDRAWAL,
                merchantId, "WITHDRAWAL", saved.id(),
                Map.of("walletBalance", balanceBefore.amount()),
                Map.of("walletBalance", merchant.walletBalance().amount(), "amount", amount.amount()),
                reason, ip);
        return new AdminWithdrawal(saved, merchant.walletBalance());
    }

    /** Resultado da Fase A do saque do vendedor (saque PROCESSING + saldo pós-débito). */
    public record MerchantWithdrawal(Withdrawal withdrawal, Money walletBalance) {
    }

    /** Resultado da Fase A do saque administrativo (saque PROCESSING + saldo pós-débito). */
    public record AdminWithdrawal(Withdrawal withdrawal, Money walletBalance) {
    }
}
