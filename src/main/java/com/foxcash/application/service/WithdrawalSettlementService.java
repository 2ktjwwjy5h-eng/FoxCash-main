package com.foxcash.application.service;

import com.foxcash.domain.exception.WithdrawalNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.revenue.MasterLedgerEntryType;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.model.withdrawal.WithdrawalStatus;
import com.foxcash.domain.model.withdrawal.WithdrawalType;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.WithdrawalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Liquida o desfecho de um saque {@code PROCESSING} (Spec 36) — compartilhado
 * pelo webhook de saida do PSP ({@code POST /webhooks/payouts}) e pela
 * reconciliacao de orfaos.
 *
 * <p>{@code PAID} → {@code COMPLETED} (publica o evento {@code
 * WITHDRAWAL_COMPLETED} do outbox — Spec 35). {@code FAILED} → {@code FAILED}
 * com motivo e estorno automatico do debito ao saldo (credit + ledger
 * {@code WITHDRAWAL_REVERSAL} na mesma transacao). Idempotente: o gate de
 * status ({@code != PROCESSING} → no-op) impede re-entrega de duplicar estorno,
 * sob lock pessimista do saque ({@code findByExternalReferenceForUpdate}).
 */
@Service
public class WithdrawalSettlementService {

    private final WithdrawalRepository withdrawalRepository;
    private final MerchantRepository merchantRepository;
    private final WalletLedgerRecorder ledgerRecorder;
    private final MasterLedgerRecorder masterLedgerRecorder;
    private final WebhookEventPublisher webhookEventPublisher;
    private final PlatformActivityRecorder activityRecorder;

    public WithdrawalSettlementService(WithdrawalRepository withdrawalRepository,
                                       MerchantRepository merchantRepository,
                                       WalletLedgerRecorder ledgerRecorder,
                                       MasterLedgerRecorder masterLedgerRecorder,
                                       WebhookEventPublisher webhookEventPublisher,
                                       PlatformActivityRecorder activityRecorder) {
        this.withdrawalRepository = withdrawalRepository;
        this.merchantRepository = merchantRepository;
        this.ledgerRecorder = ledgerRecorder;
        this.masterLedgerRecorder = masterLedgerRecorder;
        this.webhookEventPublisher = webhookEventPublisher;
        this.activityRecorder = activityRecorder;
    }

    /**
     * Liquida pelo {@code externalReference} do PSP sob lock pessimista.
     *
     * @param pspStatus   vocabulario do PSP: "PAID" | "FAILED"
     * @param failedReason motivo da falha (usado quando pspStatus=FAILED)
     */
    @Transactional
    public void settleByExternalReference(String externalReference, String pspStatus, String failedReason) {
        Withdrawal withdrawal = withdrawalRepository.findByExternalReferenceForUpdate(externalReference)
                .orElseThrow(() -> new WithdrawalNotFoundException(externalReference));
        settleOutcome(withdrawal, pspStatus, failedReason);
    }

    /**
     * Estorno de FALHA SÍNCRONA do PSP no saque (auditoria 2026-08-05/B-04): o
     * provider foi chamado fora da transação e falhou — o saque vira
     * {@code FAILED} com motivo e o débito é estornado ao saldo (mesmo fluxo do
     * webhook/reconciliação, {@link #settleOutcome}).
     *
     * <p><b>SEC-22 (revisão de segurança 2026-08-11):</b> a leitura usa
     * {@code findByIdForUpdate} (lock pessimista) — mesma serialização do
     * webhook/reconciliação ({@code findByExternalReferenceForUpdate}). Antes,
     * o {@code findById} sem lock deixava o gate de status
     * ({@code status != PROCESSING → no-op}) operar sobre snapshot stale: um
     * settlement concorrente (ex.: o PSP aceitou mas o createPayout lançou
     * timeout — o webhook PAID chega em paralelo) podia ser sobrescrito —
     * FAILED+estorno e COMPLETED ao mesmo tempo (efeito financeiro duplicado).
     */
    @Transactional
    public void settleFailedByWithdrawalId(UUID withdrawalId, String failedReason) {
        // SEC-22: lock pessimista (findByIdForUpdate) — serializa com o
        // webhook/reconciliação; sem o lock o gate de status operava sobre
        // snapshot stale e um settlement concorrente podia ser sobrescrito.
        Withdrawal withdrawal = withdrawalRepository.findByIdForUpdate(withdrawalId)
                .orElseThrow(() -> new WithdrawalNotFoundException(withdrawalId));
        settleOutcome(withdrawal, "FAILED", failedReason);
    }

    /** Maquina de estados: PROCESSING -> COMPLETED | FAILED (idempotente). */
    public void settleOutcome(Withdrawal withdrawal, String pspStatus, String failedReason) {
        if (withdrawal.status() != WithdrawalStatus.PROCESSING) {
            return; // idempotente: re-entrega / re-execução nao duplica
        }
        if ("PAID".equals(pspStatus)) {
            Withdrawal completed = withdrawal.complete(Instant.now());
            withdrawalRepository.save(completed);
            // Webhook de saida (Spec 35): saque CONCLUIDO (nao na solicitacao).
            webhookEventPublisher.publish(completed.merchantId(), WebhookEventType.WITHDRAWAL_COMPLETED,
                    Map.of("withdrawalId", completed.id(), "amount", completed.amount().amount(),
                            "type", completed.type().name()));
            // Spec 41: relatório de atividades.
            activityRecorder.record(PlatformActorType.MERCHANT, completed.merchantId(),
                    PlatformAction.WITHDRAWAL_COMPLETED, "WITHDRAWAL", completed.id(),
                    completed.merchantId(), Map.of("amount", completed.amount().amount()));
        } else if ("FAILED".equals(pspStatus)) {
            Withdrawal failed = withdrawal.fail(
                    failedReason == null || failedReason.isBlank() ? "Falha no PSP" : failedReason,
                    Instant.now());
            withdrawalRepository.save(failed);
            // Estorno automatico do debito ao saldo (Spec 36): credit + ledger
            // WITHDRAWAL_REVERSAL na MESMA transacao, sob lock do merchant.
            // Spec 88: devolve valor + taxa (crédito único no saldo) e registra
            // WITHDRAWAL_FEE_REVERSAL quando houve taxa.
            merchantRepository.findByIdForUpdate(failed.merchantId()).ifPresent(merchant -> {
                merchant.credit(failed.amount().add(failed.fee()));
                merchantRepository.save(merchant);
                ledgerRecorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_REVERSAL,
                        failed.amount(), "WITHDRAWAL", failed.id());
                if (failed.fee().amount().signum() > 0) {
                    ledgerRecorder.record(merchant, WalletLedgerEntryType.WITHDRAWAL_FEE_REVERSAL,
                            failed.fee(), "WITHDRAWAL", failed.id());
                }
            });
            // SEC-29 (revisão de segurança 2026-08-11): saque ADMINISTRATIVO
            // (Spec 32) registrou ADMIN_RECOVERY no ledger do master na Fase A —
            // o estorno devolve o valor ao lojista, então a receita registrada
            // precisa ser REVERTIDA (mesma transação, gate de status idempotente
            // → reversão única). Sem isso o relatório de receita (Spec 34)
            // mostra valor recolhido que não ocorreu.
            if (failed.type() == WithdrawalType.ADMINISTRATIVE) {
                masterLedgerRecorder.record(failed.merchantId(),
                        MasterLedgerEntryType.ADMIN_RECOVERY_REVERSAL,
                        failed.amount(), "WITHDRAWAL", failed.id());
            } else if (failed.fee().amount().signum() > 0) {
                // Spec 88 (padrão SEC-29): saque do vendedor com taxa que falhou —
                // o estorno devolveu a taxa ao lojista, então a receita
                // WITHDRAWAL_FEE registrada na Fase A é revertida.
                masterLedgerRecorder.record(failed.merchantId(),
                        MasterLedgerEntryType.WITHDRAWAL_FEE_REVERSAL,
                        failed.fee(), "WITHDRAWAL", failed.id());
            }
            activityRecorder.record(PlatformActorType.MERCHANT, failed.merchantId(),
                    PlatformAction.WITHDRAWAL_FAILED, "WITHDRAWAL", failed.id(),
                    failed.merchantId(), Map.of("amount", failed.amount().amount()));
        } else {
            throw new IllegalArgumentException("Status de payout desconhecido: " + pspStatus);
        }
    }
}
