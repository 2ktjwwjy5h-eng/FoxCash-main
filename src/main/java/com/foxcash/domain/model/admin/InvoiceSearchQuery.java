package com.foxcash.domain.model.admin;

import com.foxcash.domain.model.billing.BillingPeriod;
import com.foxcash.domain.model.billing.InvoiceStatus;

import java.util.UUID;

/**
 * Filtros/paginação da consulta administrativa de faturas (dashboard).
 *
 * @param merchantId filtro por lojista (opcional)
 * @param status     filtro por status da fatura (opcional)
 * @param period     filtro por período de faturamento (opcional)
 * @param page       número da página (0-based)
 * @param size       tamanho da página
 */
public record InvoiceSearchQuery(
        UUID merchantId,
        InvoiceStatus status,
        BillingPeriod period,
        int page,
        int size) {
}
