package com.foxcash.infrastructure.inbound.rest.dto;

/**
 * Request de criação/atualização de mensagem da loja (Spec 51) — {@code event}
 * (WELCOME/AWAITING_PAYMENT/PAYMENT_RECEIVED/DELIVERED/PROMO/FAQ), {@code text}
 * (1–1000) e {@code active} opcional.
 *
 * <p>No upsert ({@code POST}) o {@code event} é obrigatório e 1 por etapa; no
 * {@code PATCH} os campos nulos significam "não alterar". A validação final é
 * do domínio — erros viram 400 {@code invalid_argument}.
 */
public record StoreMessageRequest(
        String event,
        String text,
        Boolean active) {
}
