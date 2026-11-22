package com.foxcash.domain.port.in;

import java.util.UUID;

/**
 * Porta de entrada: registro de acesso ao conteúdo entregue (Spec 49+,
 * 2026-08-06) — o bot informa quando o comprador acessou o produto (prova de
 * entrega em disputa de chargeback). Por {@code merchantId} do principal
 * (404 cross-loja sem revelar existência).
 */
public interface DeliveryAccessRecordingUseCase {

    /**
     * Registra um acesso na entrega {@code deliveryId}.
     *
     * @param merchantId dono da entrega (do principal da sessão)
     * @param deliveryId entrega (404 {@code delivery_not_found} se não é da loja)
     * @param ip         IP de origem (capturado pelo servidor; pode ser null)
     * @param device     User-Agent (capturado pelo servidor; pode ser null)
     * @param note       detalhe livre do bot (ex.: "acessou o link"); pode ser null
     */
    void record(UUID merchantId, UUID deliveryId, String ip, String device, String note);
}
