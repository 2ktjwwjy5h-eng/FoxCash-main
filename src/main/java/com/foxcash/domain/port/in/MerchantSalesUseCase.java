package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Porta de entrada: vendas/operação comercial do lojista no portal (Spec 25) —
 * somente leitura. Listagem paginada com filtros, métricas de topo (volume 30d,
 * concluídas, pendentes, conversão) e leitura de detalhe com payload Pix quando
 * {@code PENDING}. Estorno/reembolso fora de escopo (decisão do master/PSP).
 */
public interface MerchantSalesUseCase {

    /**
     * Vendas do lojista paginadas (createdAt DESC). {@code status}, {@code from}
     * e {@code to} são opcionais (null = sem filtro). {@code profileId} (Spec 124)
     * opcional: null → lojista inteiro (regressão); presente → só o perfil
     * (header inválido → vazio, nunca vaza).
     */
    PageResult<Sale> list(UUID merchantId, UUID profileId, TransactionStatus status,
                          Instant from, Instant to, int page, int size);

    /**
     * Métricas de topo dos últimos {@code days} dias (clamp 1–90, default 30):
     * volume aprovado, concluídas, pendentes e taxa de conversão
     * (concluídas ÷ criadas no período × 100; 0 sem transações). {@code profileId}
     * (Spec 124) opcional — mesmo escopo do {@code list}.
     */
    Metrics metrics(UUID merchantId, UUID profileId, int days);

    /**
     * Detalhe de uma venda do lojista. Inexistente → {@code TransactionNotFoundException};
     * transação de outro lojista → {@code MerchantAccessDeniedException} (Spec 18).
     * Com {@code profileId} (Spec 124), transação de outro perfil → mesma exceção
     * (não vaza). Payload Pix preenchido somente quando {@code PENDING}.
     */
    SaleDetail detail(UUID merchantId, UUID profileId, UUID transactionId);

    /** Item da listagem: bruto e líquido (bruto − taxa do plano) da venda. */
    record Sale(UUID transactionId, String buyerIdHash, Money grossAmount,
                Money netAmount, TransactionStatus status, Instant createdAt) {
    }

    /** Métricas de topo da tela Vendas (Spec 25). */
    record Metrics(BigDecimal volume30d, long completedSales, long pendingSales,
                   BigDecimal conversionRate) {
    }

    /** Detalhe da venda: campos da listagem + payload Pix (só {@code PENDING}). */
    record SaleDetail(UUID transactionId, String buyerIdHash, Money grossAmount,
                      Money netAmount, TransactionStatus status, Instant createdAt,
                      Instant paidAt, String description, String pixKey,
                      String pixQrCode, String pixCopyPaste, String endToEndId) {
    }
}
