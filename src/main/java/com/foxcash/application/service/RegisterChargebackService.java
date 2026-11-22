package com.foxcash.application.service;

import com.foxcash.domain.exception.ChargebackAlreadyExistsException;
import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.fraud.Chargeback;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.in.RegisterChargebackUseCase;
import com.foxcash.domain.port.out.ChargebackRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicacao: registra um chargeback contra uma transacao (Spec 19).
 *
 * <p>Valida que a transacao existe, pertence ao lojista informado, está
 * {@code APPROVED} e nao ha chargeback duplicado (um por transacao). O
 * chargeback alimenta: (1) o indicador de risco da Spec 16, (2) o bloqueio em
 * tempo real do comprador (buyerId com historico de chargeback) e (3) o
 * estorno financeiro — o valor aprovado sai do wallet (QA 2026-08-04).
 *
 * <p><b>Fonte única de débito (Spec 29):</b> o chargeback MARCA a transação
 * {@code REFUNDED} (transição {@code refund()}) — o webhook {@code REFUNDED}
 * posterior vira no-op e o débito acontece UMA única vez (1 entry
 * {@code CHARGEBACK_DEBIT}). Duplicado (sequencial ou em corrida) → 409
 * {@code chargeback_already_exists}.
 */
@Service
public class RegisterChargebackService implements RegisterChargebackUseCase {

    private final TransactionRepository transactionRepository;
    private final ChargebackRepository chargebackRepository;
    private final MerchantRepository merchantRepository;
    private final WalletLedgerRecorder ledgerRecorder;
    private final ReceivableRecorder receivableRecorder;
    private final WebhookEventPublisher webhookEventPublisher;
    private final PlatformActivityRecorder activityRecorder;

    public RegisterChargebackService(TransactionRepository transactionRepository,
                                     ChargebackRepository chargebackRepository,
                                     MerchantRepository merchantRepository,
                                     WalletLedgerRecorder ledgerRecorder,
                                     ReceivableRecorder receivableRecorder,
                                     WebhookEventPublisher webhookEventPublisher,
                                     PlatformActivityRecorder activityRecorder) {
        this.transactionRepository = transactionRepository;
        this.chargebackRepository = chargebackRepository;
        this.merchantRepository = merchantRepository;
        this.ledgerRecorder = ledgerRecorder;
        this.receivableRecorder = receivableRecorder;
        this.webhookEventPublisher = webhookEventPublisher;
        this.activityRecorder = activityRecorder;
    }

    @Override
    @Transactional
    public Result register(UUID merchantId, UUID transactionId, String reason) {
        // Lock pessimista na transacao: serializa registros concorrentes de
        // chargeback para a MESMA transacao — o segundo vê o primeiro e responde
        // 409 canonical (antes a corrida caía na unique e virava 500).
        PixTransaction transaction = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        if (!transaction.merchantId().equals(merchantId)) {
            throw new IllegalArgumentException("Transacao nao pertence ao lojista informado");
        }
        if (chargebackRepository.findByTransactionId(transactionId).isPresent()) {
            throw new ChargebackAlreadyExistsException(transactionId);
        }
        if (transaction.status() != TransactionStatus.APPROVED) {
            throw new IllegalStateException("Chargeback so pode ser registrado em transacao APPROVED");
        }

        // Fonte única de débito (Spec 29): o chargeback MARCA a transação
        // REFUNDED — o webhook REFUNDED posterior vira no-op (status já
        // REFUNDED) e o débito acontece uma única vez. A venda estornada deixa
        // de aparecer como concluída no portal (Spec 25 filtra por APPROVED).
        transaction.refund();
        transactionRepository.save(transaction);

        Chargeback saved;
        try {
            saved = chargebackRepository.save(Chargeback.register(
                    merchantId, transactionId, transaction.buyerIdHash(), transaction.amount(), reason));
        } catch (DataIntegrityViolationException e) {
            // Backstop: corrida extrema na unique uq_chargebacks_transaction.
            // O rollback da transação desfaz o refund() acima — nenhum efeito duplicado.
            throw new ChargebackAlreadyExistsException(transactionId);
        }

        // Estorno financeiro (QA 2026-08-04): o valor aprovado sai do wallet —
        // pode ficar negativo se o lojista já sacou. Lock pessimista serializa
        // com créditos/saques do mesmo lojista. Ledger (Spec 27): 1 entry
        // CHARGEBACK_DEBIT na mesma transação (referência = chargeback).
        Merchant merchant = merchantRepository.findByIdForUpdate(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        merchant.debit(transaction.amount());
        merchantRepository.save(merchant);
        ledgerRecorder.record(merchant, WalletLedgerEntryType.CHARGEBACK_DEBIT,
                transaction.amount(), "CHARGEBACK", saved.id());
        // Divida formal (Spec 33): saldo negativo apos o chargeback -> receivable
        // CHARGEBACK na MESMA transacao (origem = chargeback). A recuperacao com
        // saldo disponivel e feita pelo job de cobranca (Spec 33).
        receivableRecorder.recordFromNegativeBalance(merchant, ReceivableOrigin.CHARGEBACK,
                saved.id(), Instant.now());
        // Webhook de saida (Spec 35): chargeback registrado na MESMA transacao.
        webhookEventPublisher.publish(merchantId, WebhookEventType.CHARGEBACK_REGISTERED,
                Map.of("transactionId", transactionId, "chargebackId", saved.id(),
                        "amount", transaction.amount().amount()));
        // Spec 41: relatório de atividades — chargeback registrado.
        activityRecorder.record(PlatformActorType.MERCHANT, merchantId,
                PlatformAction.CHARGEBACK_REGISTERED, "TRANSACTION", transactionId,
                merchantId, Map.of("amount", transaction.amount().amount()));
        return new Result(saved.id());
    }
}
