package com.foxcash.infrastructure.inbound.rest.dto;

import jakarta.validation.constraints.Size;

/**
 * Request de registro de acesso ao conteúdo entregue (Spec 49+, 2026-08-06) —
 * o bot informa quando o comprador acessou o produto. O IP e o User-Agent são
 * capturados pelo servidor no header — o bot envia apenas o {@code note}
 * opcional (detalhe livre, ex.: "acessou o link do servidor").
 */
public record DeliveryAccessRequest(
        @Size(max = 255) String note) {
}
