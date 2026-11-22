package com.foxcash.domain.port.out;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.billing.ReceivableSearchQuery;
import com.foxcash.domain.model.billing.ReceivableStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: persistencia de receivables (contas a receber, Spec 33).
 */
public interface ReceivableRepository {

    Receivable save(Receivable receivable);

    Optional<Receivable> findById(UUID id);

    /**
     * Receivable em aberto ({@code OPEN}/{@code PARTIAL}) de uma origem — usado
     * pelo {@code ReceivableRecorder} (idempotencia na criacao por estorno) e
     * pelo upsert da fatura. A unique {@code uq_receivables_origin} e o backstop.
     */
    Optional<Receivable> findOpenByOrigin(UUID merchantId, ReceivableOrigin origin, UUID referenceId);

    /**
     * Todos os receivables em aberto das origens informadas (estorno) — usado
     * pelo job para quitar a divida de estorno com o saldo disponivel.
     */
    List<Receivable> findOpenByOrigins(List<ReceivableOrigin> origins);

    /** Flag de inadimplencia: existe receivable {@code OPEN}/{@code PARTIAL}. */
    boolean hasOpenDebt(UUID merchantId);

    /**
     * Marca {@code PAID} o receivable {@code OPEN}/{@code PARTIAL} de uma origem
     * (fatura quitada) — no-op se nao existir (idempotente).
     */
    void markPaidIfOpen(UUID merchantId, ReceivableOrigin origin, UUID referenceId, Instant now);

    /**
     * Cria ou atualiza o receivable aberto de uma origem com o saldo devedor
     * atual ({@code remainingDebt}, positivo). Usado na cobranca parcial de
     * fatura. Invariante do dominio: {@code amount} e a divida TOTAL original
     * (fixa na abertura), {@code paidAmount} acumula o pago contra ela e
     * {@code remaining() = amount - paidAmount} e o saldo devedor atual —
     * nunca negativo.
     */
    Receivable upsertOpen(UUID merchantId, ReceivableOrigin origin, UUID referenceId,
                          Money remainingDebt, Instant now);

    /** Consulta paginada com filtros (master, Spec 33). */
    PageResult<Receivable> search(ReceivableSearchQuery query);
}
