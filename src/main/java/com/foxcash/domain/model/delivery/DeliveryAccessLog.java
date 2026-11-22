package com.foxcash.domain.model.delivery;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Log de acesso ao conteúdo entregue (Spec 49+, 2026-08-06) — prova de que o
 * comprador usou o produto (evidência em disputa de chargeback). Registrado
 * pelo bot do lojista via {@code POST /api/v1/foxcash/deliveries/{id}/access}
 * quando o comprador acessa o conteúdo (cargo, link, arquivo, etc.).
 *
 * <p>{@code ip} e {@code device} seguem o mesmo padrão da trilha de auditoria
 * (IP cru truncado em 45 + User-Agent truncado em 255). {@code note} é um
 * detalhe livre do bot (ex.: "acessou o link do servidor").
 */
public class DeliveryAccessLog {

    private final UUID id;
    private final UUID deliveryId;
    private final Instant accessedAt;
    private final String ip;
    private final String device;
    private final String note;

    private DeliveryAccessLog(UUID id, UUID deliveryId, Instant accessedAt,
                              String ip, String device, String note) {
        this.id = id;
        this.deliveryId = deliveryId;
        this.accessedAt = accessedAt;
        this.ip = ip;
        this.device = device;
        this.note = note;
    }

    public static DeliveryAccessLog create(UUID deliveryId, Instant now,
                                           String ip, String device, String note) {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new DeliveryAccessLog(UUID.randomUUID(), deliveryId, now, ip, device, note);
    }

    public static DeliveryAccessLog restore(UUID id, UUID deliveryId, Instant accessedAt,
                                            String ip, String device, String note) {
        return new DeliveryAccessLog(id, deliveryId, accessedAt, ip, device, note);
    }

    public UUID id() {
        return id;
    }

    public UUID deliveryId() {
        return deliveryId;
    }

    public Instant accessedAt() {
        return accessedAt;
    }

    public String ip() {
        return ip;
    }

    public String device() {
        return device;
    }

    public String note() {
        return note;
    }
}
