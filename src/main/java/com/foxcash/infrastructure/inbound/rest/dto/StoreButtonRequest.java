package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.Map;

/**
 * Request de criação/atualização de botão do menu (Spec 50) — {@code label}
 * (1–80), {@code action} (BUY/PREVIEW/FAQ/SOCIAL/LINK/CLOSE) e {@code payload}
 * (objeto JSON validado por tipo; ex.: {@code {mediaUrls[]}}, {@code {text}},
 * {@code {url, label?}}).
 *
 * <p>No {@code PATCH} os campos nulos significam "não alterar"; no {@code POST}
 * o {@code label}/{@code action} são obrigatórios (o domínio valida). A
 * validação final é do domínio/codec — erros viram 400 {@code invalid_argument}.
 */
public record StoreButtonRequest(
        String label,
        String action,
        Map<String, Object> payload,
        Boolean active) {
}
