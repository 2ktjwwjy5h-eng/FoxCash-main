package com.foxcash.application.service;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.exception.TransactionNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.in.ProcessPixWebhookUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.ReconciliationIssueRepository;
import com.foxcash.domain.port.out.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Aplicacao: processa eventos de webhook do provedor Pix e atualiza a transacao.
 *
 * <p>O PSP envia o status em vocabulario proprio (ex.: "PAID"); aqui ele e
 * normalizado para o {@link TransactionStatus} canonico do dominio
 * (PAID -> APPROVED, demais por nome).
 */
@Service
public class ProcessPixWebhookService implements ProcessPixWebhookUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessPixWebhookService.class);

    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;
    private final WalletLedgerRecorder ledgerRecorder;
    private final ReceivableRecorder receivableRecorder;
    private final WebhookEventPublisher webhookEventPublisher;
    private final PlatformActivityRecorder activityRecorder;
    private final ReconciliationIssueRepository reconciliationIssues;
    private final DeliveryRecorder deliveryRecorder;
    private final BotFunnelService botFunnelService;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public ProcessPixWebhookService(TransactionRepository transactionRepository,
                                    MerchantRepository merchantRepository,
                                    WalletLedgerRecorder ledgerRecorder,
                                    ReceivableRecorder receivableRecorder,
                                    WebhookEventPublisher webhookEventPublisher,
                                    PlatformActivityRecorder activityRecorder,
                                    ReconciliationIssueRepository reconciliationIssues,
                                    DeliveryRecorder deliveryRecorder,
                                    BotFunnelService botFunnelService,
                                    io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.merchantRepository = merchantRepository;
        this.ledgerRecorder = ledgerRecorder;
        this.receivableRecorder = receivableRecorder;
        this.webhookEventPublisher = webhookEventPublisher;
        this.activityRecorder = activityRecorder;
        this.reconciliationIssues = reconciliationIssues;
        this.deliveryRecorder = deliveryRecorder;
        this.botFunnelService = botFunnelService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional
    public void process(WebhookEvent event) {
        // Lock pessimista na transação: serializa o processamento de eventos
        // concorrentes da mesma cobrança (replay de PAID em paralelo não
        // duplica o crédito do wallet — o segundo vê APPROVED e retorna no-op).
        PixTransaction transaction = transactionRepository.findByExternalReferenceForUpdate(event.externalReference())
                .orElseThrow(() -> new TransactionNotFoundException(event.externalReference()));

        TransactionStatus status = mapStatus(event.status());

        // Idempotência (Spec 18): replay de um evento já aplicado (ex.: PSP
        // reenvia PAID) é no-op — não lança conflito nem duplica efeito.
        if (transaction.status() == status) {
            return;
        }

        // Auditoria 2026-08-05 (M13): eventos fora de ordem (ex.: PAID tardio
        // apos REFUNDED, REFUNDED de cobranca nunca paga) viram NO-OP em vez de
        // 500 — o PSP nao fica reenviando para sempre e nao ha efeito indevido.
        boolean changed = false;
        switch (status) {
            case APPROVED -> {
                if (transaction.status() == TransactionStatus.PENDING) {
                    // B-05 (auditoria 2026-08-05): o valor informado pelo PSP é
                    // validado contra o registro local — divergência NÃO aprova às
                    // cegas (no-op + issue de reconciliação para o operador resolver).
                    if (amountDiverges(event, transaction)) {
                        return;
                    }
                    transaction.approve();
                    creditWallet(transaction);
                    publishSaleEvent(transaction, WebhookEventType.SALE_PAID);
                    activityRecorder.record(PlatformActorType.SYSTEM, null, PlatformAction.SALE_PAID,
                            "TRANSACTION", transaction.id(), transaction.merchantId(),
                            Map.of("amount", transaction.amount().amount()));
                    // Spec 49: transação de plano aprovada → grava a entrega na
                    // MESMA transação (destinatário + snapshot das ações).
                    deliveryRecorder.recordFor(transaction);
                    // Spec 75: funil — venda paga atribuída a bot (se houver) na
                    // MESMA transação do PAID (idempotente por bot+transação).
                    botFunnelService.recordPaid(transaction);
                    changed = true;
                }
            }
            case REJECTED -> {
                if (transaction.status() == TransactionStatus.PENDING) {
                    transaction.reject();
                    changed = true;
                }
            }
            case REFUNDED -> {
                if (transaction.status() == TransactionStatus.APPROVED) {
                    transaction.refund();
                    debitWallet(transaction);
                    publishSaleEvent(transaction, WebhookEventType.SALE_REFUNDED);
                    activityRecorder.record(PlatformActorType.SYSTEM, null, PlatformAction.SALE_REFUNDED,
                            "TRANSACTION", transaction.id(), transaction.merchantId(),
                            Map.of("amount", transaction.amount().amount()));
                    changed = true;
                }
            }
            default -> throw new IllegalArgumentException(
                    "Webhook status nao suportado para transacao: " + event.status());
        }

        if (changed) {
            transactionRepository.save(transaction);
        }
    }

    /**
     * B-05 (auditoria 2026-08-05): valida o {@code amount} do PSP contra o valor
     * local na aprovação. Quando o PSP informa um valor divergente, a transação
     * NÃO é aprovada às cegas — o caso vira no-op com issue de reconciliação (o
     * operador decide), em vez de creditar um valor divergente. PSP sem
     * {@code amount} → confia no registro (comportamento atual).
     */
    private boolean amountDiverges(WebhookEvent event, PixTransaction transaction) {
        BigDecimal pspAmount = event.amount();
        if (pspAmount == null) {
            return false;
        }
        if (pspAmount.compareTo(transaction.amount().amount()) != 0) {
            log.warn("Divergencia de valor no webhook PAID: transacao {} registrada em {}, PSP informou {}",
                    transaction.id(), transaction.amount().amount(), pspAmount);
            // Spec 126: métrica financeira de divergência B-05 (alerta proativo).
            meterRegistry.counter("foxcash_webhook_amount_divergence_total").increment();
            reconciliationIssues.open(transaction.merchantId(),
                    transaction.amount(), Money.of(pspAmount), Instant.now());
            return true;
        }
        return false;
    }

    /** Webhook de saida (Spec 35): venda paga/estornada na MESMA transacao. */
    private void publishSaleEvent(PixTransaction transaction, WebhookEventType type) {
        Map<String, Object> data = new HashMap<>();
        data.put("transactionId", transaction.id());
        data.put("amount", transaction.amount().amount());
        data.put("paidAt", transaction.paidAt());
        // Spec 127: data.traceId explícito — correlação de logs ponta a ponta
        // (o worker do outbox loga com o MESMO traceId no MDC).
        data.put("traceId", transaction.traceId());
        // Spec 125 (trace): o evento carrega a referência da transação — o trace
        // lista a entrega do SALE_PAID/SALE_REFUNDED na jornada.
        webhookEventPublisher.publish(transaction.merchantId(), type, data,
                "TRANSACTION", transaction.id());
    }

    /**
     * Credita o wallet do lojista na aprovacao (Spec 14): a venda aprovada e a
     * fonte de saldo dos saques. Idempotente — o replay de PAID retorna antes
     * (transacao ja APPROVED), entao o credito so ocorre uma vez. O lock
     * pessimista no lojista evita perda de atualização entre vendas
     * concorrentes do mesmo lojista.
     */
    private void creditWallet(PixTransaction transaction) {
        Merchant merchant = merchantRepository.findByIdForUpdate(transaction.merchantId())
                .orElseThrow(() -> new MerchantNotFoundException(transaction.merchantId()));
        merchant.credit(transaction.amount());
        merchantRepository.save(merchant);
        // Ledger (Spec 27): a venda aprovada gera UMA entry SALE_CREDIT na mesma
        // transação, com balance_after = saldo pós-crédito.
        ledgerRecorder.record(merchant, WalletLedgerEntryType.SALE_CREDIT,
                transaction.amount(), "TRANSACTION", transaction.id());
    }

    /**
     * Estorno (QA 2026-08-04): devolução/refund debita o wallet — o valor não
     * fica mais disponível para saque. Pode deixar o saldo negativo quando o
     * lojista já sacou (o lojista deve à plataforma). Lock pessimista para
     * serializar com saques/créditos do mesmo lojista.
     */
    private void debitWallet(PixTransaction transaction) {
        Merchant merchant = merchantRepository.findByIdForUpdate(transaction.merchantId())
                .orElseThrow(() -> new MerchantNotFoundException(transaction.merchantId()));
        merchant.debit(transaction.amount());
        merchantRepository.save(merchant);
        // Ledger (Spec 27): o estorno gera UMA entry REFUND_DEBIT na mesma
        // transação, com balance_after = saldo pós-débito (pode ser negativo).
        ledgerRecorder.record(merchant, WalletLedgerEntryType.REFUND_DEBIT,
                transaction.amount(), "TRANSACTION", transaction.id());
        // Divida formal (Spec 33): saldo negativo apos o refund -> receivable
        // REFUND na MESMA transacao (origem = transacao). A recuperacao com
        // saldo disponivel e feita pelo job de cobranca (Spec 33).
        receivableRecorder.recordFromNegativeBalance(merchant, ReceivableOrigin.REFUND,
                transaction.id(), Instant.now());
    }

    private TransactionStatus mapStatus(String rawStatus) {
        if ("PAID".equalsIgnoreCase(rawStatus)) {
            return TransactionStatus.APPROVED;
        }
        try {
            return TransactionStatus.valueOf(rawStatus.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Status de webhook invalido: " + rawStatus, e);
        }
    }
}
