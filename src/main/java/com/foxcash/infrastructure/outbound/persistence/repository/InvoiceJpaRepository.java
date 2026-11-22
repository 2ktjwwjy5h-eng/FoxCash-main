package com.foxcash.infrastructure.outbound.persistence.repository;

import com.foxcash.domain.model.billing.InvoiceStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.InvoiceJpaEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorio Spring Data (infraestrutura) para faturas.
 */
public interface InvoiceJpaRepository extends JpaRepository<InvoiceJpaEntity, UUID>,
        JpaSpecificationExecutor<InvoiceJpaEntity> {

    Optional<InvoiceJpaEntity> findByMerchantIdAndBillingPeriod(UUID merchantId, String billingPeriod);

    /** Lista do lojista limitada (auditoria 2026-08-05: nunca carrega tudo). */
    List<InvoiceJpaEntity> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    long countByStatus(InvoiceStatus status);

    @Query("SELECT COALESCE(SUM(i.totalAmount), 0) FROM InvoiceJpaEntity i WHERE i.status IN :statuses")
    BigDecimal sumTotalAmountByStatuses(@Param("statuses") Collection<InvoiceStatus> statuses);

    /**
     * Faturas nao quitadas e vencidas ate {@code dueDate} (Spec 33): PENDING ou
     * OVERDUE com saldo devedor ({@code totalAmount > paidAmount}).
     */
    @Query("SELECT i FROM InvoiceJpaEntity i WHERE i.status IN :statuses "
            + "AND i.dueDate <= :dueDate AND i.totalAmount > i.paidAmount")
    List<InvoiceJpaEntity> findUnpaidDueOnOrBefore(@Param("statuses") Collection<InvoiceStatus> statuses,
                                                   @Param("dueDate") LocalDate dueDate);
}
