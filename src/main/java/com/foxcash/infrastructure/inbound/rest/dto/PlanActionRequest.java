package com.foxcash.infrastructure.inbound.rest.dto;

import java.util.Map;

/**
 * Request de ação de entrega (Spec 49) — {@code seq} (ordem), {@code type}
 * (enum) e {@code payload} (objeto JSON validado por tipo; ex.:
 * {@code {"roleId":"..."}}, {@code {"text":"..."}}, {@code {"url":"...",
 * "label":"..."}}).
 */
public record PlanActionRequest(
        Integer seq,
        String type,
        Map<String, Object> payload) {
}
