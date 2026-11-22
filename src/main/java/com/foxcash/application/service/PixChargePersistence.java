package com.foxcash.application.service;

import com.foxcash.domain.model.notification.WebhookEventType;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.domain.port.out.PixProviderClient;
import com.foxcash.domain.port.out.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Persistencia das cobrancas Pix em etapas (auditoria 2026-08-05): o PSP e
 * chamado APOS o commit da transacao {@code PENDING} — a conexao do pool nao
 * fica retida durante o I/O de rede da cobranca (Pagar.me, timeout de 10s) e,
 * se o save local falhar, nenhuma cobranca orfa fica no provedor (nada e
 * criado no PSP antes de a transacao local existir).
 *
 * <p>A persistência antecipada da {@code PENDING} com a chave de idempotência
 * (Spec 28) é feita pelo {@link IdempotencyPersister} (transação própria,
 * {@code REQUIRES_NEW}) — este componente cuida apenas das etapas posteriores.
 */
@Service
public class PixChargePersistence {

    private final TransactionRepository transactionRepository;
    private final WebhookEventPublisher webhookEventPublisher;

    public PixChargePersistence(TransactionRepository transactionRepository,
                                WebhookEventPublisher webhookEventPublisher) {
        this.transactionRepository = transactionRepository;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    /** Anexa o payload da cobranca retornado pelo PSP (transacao propria) — sem evento. */
    @Transactional
    public PixTransaction attach(PixTransaction pending, PixProviderClient.CreateChargeResult charge) {
        // Spec 91: o router carrega o provider no resultado (null com adapter direto).
        pending.attachCharge(charge.externalReference(), charge.pixQrCode(), charge.pixCopyPaste(),
                charge.provider());
        return transactionRepository.save(pending);
    }

    /** Anexa a cobranca e publica {@code CHARGE_CREATED} na MESMA transacao. */
    @Transactional
    public PixTransaction attachAndPublish(PixTransaction pending, PixProviderClient.CreateChargeResult charge) {
        // Spec 62 (F4 — achado de integridade do outbox): corrida de retries.
        // Entre o commit do IdempotencyPersister (PENDING sem QR) e o attach,
        // N threads veem a MESMA transação sem external_reference e re-publicam
        // CHARGE_CREATED (medido: 26 eventos/1 transação no teste de 10k com a
        // mesma idempotencyKey). O lock serializa: o primeiro anexa + publica;
        // os demais veem external_reference já preenchido e retornam sem
        // republicar (o evento fica 1× — idempotência real do fluxo).
        Optional<PixTransaction> current = transactionRepository.findByIdForUpdate(pending.id());
        if (current.isPresent() && current.get().externalReference() != null) {
            return current.get();
        }
        PixTransaction saved = attach(pending, charge);
        // Spec 125 (trace): o evento carrega a referência da transação — o trace
        // lista a entrega do CHARGE_CREATED na jornada.
        // Spec 127: data.traceId explícito — contrato do payload auto-documentado
        // (o receptor não precisa saber que traceId == transactionId).
        webhookEventPublisher.publish(saved.merchantId(), WebhookEventType.CHARGE_CREATED,
                Map.of("transactionId", saved.id(), "amount", saved.amount().amount(),
                        "status", saved.status().name(), "traceId", saved.traceId()),
                "TRANSACTION", saved.id());
        return saved;
    }

    /** Falha inesperada (não-PSP) ao criar a cobranca: encerra a {@code PENDING} ({@code REJECTED}). */
    @Transactional
    public void cancelPending(PixTransaction pending) {
        settlePending(pending, false);
    }

    /**
     * Erro real do PSP (4xx/5xx) ao criar a cobranca (Spec 28): marca a
     * {@code PENDING} como {@code FAILED} — a chave de idempotência já produziu
     * resultado e o retry com a mesma chave responde 409 {@code idempotency_conflict}.
     */
    @Transactional
    public void failPending(PixTransaction pending) {
        settlePending(pending, true);
    }

    /**
     * SEC-26 (revisão de segurança 2026-08-11): encerra a PENDING sob lock
     * pessimista. O parâmetro {@code pending} é a snapshot carregada ANTES da
     * chamada ao PSP; se o PSP CRIOU a cobrança mas a resposta ao serviço foi
     * erro/timeout, o webhook {@code PAID} pode chegar em paralelo e já ter
     * movido a linha para {@code APPROVED} (+ crédito do wallet). Salvar a
     * snapshot por cima marcaria a venda paga como {@code FAILED} — transação
     * morta + retry 409 — mesma classe do SEC-22 (estorno do saque com snapshot
     * stale), no lado da cobrança.
     *
     * <p>Gate: somente {@code PENDING} SEM {@code external_reference} (nada
     * anexado — o PSP não devolveu cobrança) é encerrada; caso contrário o
     * desfecho fica para o webhook/reconciliação (no-op).
     */
    private void settlePending(PixTransaction pending, boolean asFailed) {
        Optional<PixTransaction> current = transactionRepository.findByIdForUpdate(pending.id());
        if (current.isEmpty()) {
            return;
        }
        PixTransaction fresh = current.get();
        if (fresh.status() != TransactionStatus.PENDING || fresh.externalReference() != null) {
            return;
        }
        if (asFailed) {
            fresh.fail();
        } else {
            fresh.reject();
        }
        transactionRepository.save(fresh);
    }
}
