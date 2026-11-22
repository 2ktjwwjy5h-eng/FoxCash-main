package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.application.service.TransactionTraceService;
import com.foxcash.domain.model.transaction.PixTransaction;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resposta do trace de transação (Spec 125) — cabeçalho da transação + linha do
 * tempo da jornada (Bot → Gateway → PSP → Ledger → Webhook).
 *
 * <p>{@code traceId} (Spec 127) é o traceId de negócio da transação (o próprio
 * {@code transactionId} hoje) — o fio condutor dos logs (MDC do worker do
 * outbox) para correlação ponta a ponta.
 *
 * <p><b>Privacidade (D7):</b> expõe apenas o cabeçalho — {@code pixKey},
 * {@code pixQrCode}, {@code pixCopyPaste}, {@code buyerIp}/{@code buyerDevice}
 * NUNCA saem no payload (a entidade de domínio completa não é serializada).
 * O {@code detail} de cada item vem da origem (activity já mascarada pelo
 * {@code PlatformActivityRecorder}).
 */
public record TransactionTraceResponse(
        UUID traceId,
        UUID transactionId,
        UUID merchantId,
        String amount,
        String status,
        String externalReference,
        String endToEndId,
        String provider,
        UUID profileId,
        String idempotencyKey,
        Instant createdAt,
        Instant paidAt,
        BotRef bot,
        List<TraceItemResponse> timeline) {

    /** Referência ao bot que originou a venda (Spec 75) — null se avulsa. */
    public record BotRef(UUID botId, String platformUserId) {
    }

    /** Item da linha do tempo — {@code stage} em {BOT, GATEWAY, PSP, LEDGER, WEBHOOK}. */
    public record TraceItemResponse(Instant at, String stage, String event, Map<String, Object> detail) {
    }

    public static TransactionTraceResponse from(TransactionTraceService.TransactionTrace trace) {
        PixTransaction tx = trace.transaction();
        BotRef bot = tx.botId() == null ? null
                : new BotRef(tx.botId(), tx.platformUserId());
        List<TraceItemResponse> items = trace.timeline().stream()
                .map(item -> new TraceItemResponse(item.at(), item.stage().name(),
                        item.event(), item.detail()))
                .toList();
        return new TransactionTraceResponse(
                tx.traceId(), tx.id(), tx.merchantId(), tx.amount().amount().toPlainString(),
                tx.status().name(), tx.externalReference(), tx.endToEndId(), tx.provider(),
                tx.profileId(), tx.idempotencyKey(), tx.createdAt(), tx.paidAt(),
                bot, items);
    }
}
