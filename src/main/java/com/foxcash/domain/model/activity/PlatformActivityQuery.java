package com.foxcash.domain.model.activity;

import java.time.Instant;
import java.util.UUID;

/**
 * Filtros/paginação da consulta do relatório de atividades (Spec 41) —
 * {@code GET /api/v1/master/report/activity}.
 *
 * @param from        início da janela ({@code createdAt}, opcional)
 * @param to          fim da janela ({@code createdAt}, opcional)
 * @param actorType   filtro por tipo de ator (MASTER/MERCHANT/SYSTEM, opcional)
 * @param action      filtro por ação (opcional)
 * @param merchantId  filtro por lojista afetado (opcional)
 * @param page        número da página (0-based)
 * @param size        tamanho da página
 * @param entityType  filtro por tipo de entidade de origem (TRANSACTION/WITHDRAWAL…,
 *                    opcional — Spec 125 trace)
 * @param entityId    filtro por id da entidade de origem (opcional — Spec 125 trace)
 */
public record PlatformActivityQuery(
        Instant from,
        Instant to,
        PlatformActorType actorType,
        PlatformAction action,
        UUID merchantId,
        int page,
        int size,
        String entityType,
        UUID entityId) {

    /** Conveniência: consulta sem filtro de entidade (chamadores existentes). */
    public PlatformActivityQuery(Instant from, Instant to, PlatformActorType actorType,
                                 PlatformAction action, UUID merchantId, int page, int size) {
        this(from, to, actorType, action, merchantId, page, size, null, null);
    }
}
