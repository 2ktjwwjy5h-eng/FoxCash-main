package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.reconciliation.ReconciliationIssue;
import com.foxcash.domain.model.reconciliation.ReconciliationIssueStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.ReconciliationIssueJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.mapper.ReconciliationIssueMapper;
import com.foxcash.infrastructure.outbound.persistence.repository.ReconciliationIssueJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da idempotência do {@code open} do adapter (Spec 30): uma issue ABERTA
 * por lojista divergente — re-execução do job não duplica (unique parcial
 * {@code uq_reconciliation_open} preserva a original, inclusive em corrida).
 */
class ReconciliationIssuePersistenceAdapterTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final ReconciliationIssueJpaRepository jpaRepository = mock(ReconciliationIssueJpaRepository.class);
    private ReconciliationIssuePersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReconciliationIssuePersistenceAdapter(jpaRepository);
    }

    @Test
    void openReusesExistingOpenIssueWithoutSaving() {
        ReconciliationIssueJpaEntity existing = new ReconciliationIssueJpaEntity(
                UUID.randomUUID(), MERCHANT_ID, new java.math.BigDecimal("100.00"),
                new java.math.BigDecimal("150.00"), ReconciliationIssueStatus.OPEN,
                Instant.now(), null);
        when(jpaRepository.findByMerchantIdAndStatus(MERCHANT_ID, ReconciliationIssueStatus.OPEN))
                .thenReturn(Optional.of(existing));

        ReconciliationIssue result = adapter.open(MERCHANT_ID, Money.of("100.00"), Money.of("150.00"), Instant.now());

        assertThat(result.id()).isEqualTo(existing.getId());
        verify(jpaRepository, never()).save(any());
    }

    @Test
    void openCreatesAndSavesNewIssueWhenNoneExists() {
        when(jpaRepository.findByMerchantIdAndStatus(MERCHANT_ID, ReconciliationIssueStatus.OPEN))
                .thenReturn(Optional.empty());
        when(jpaRepository.save(any(ReconciliationIssueJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReconciliationIssue result = adapter.open(MERCHANT_ID, Money.of("100.00"), Money.of("150.00"), Instant.now());

        assertThat(result.status()).isEqualTo(ReconciliationIssueStatus.OPEN);
        assertThat(result.expectedBalance().amount()).isEqualByComparingTo("100.00");
        assertThat(result.actualBalance().amount()).isEqualByComparingTo("150.00");
        verify(jpaRepository).save(any(ReconciliationIssueJpaEntity.class));
    }

    @Test
    void openFallsBackToExistingIssueOnUniqueViolationRace() {
        // Corrida entre jobs/instâncias: o INSERT do outro venceu — reaproveita.
        ReconciliationIssueJpaEntity existing = new ReconciliationIssueJpaEntity(
                UUID.randomUUID(), MERCHANT_ID, new java.math.BigDecimal("100.00"),
                new java.math.BigDecimal("150.00"), ReconciliationIssueStatus.OPEN,
                Instant.now(), null);
        when(jpaRepository.findByMerchantIdAndStatus(MERCHANT_ID, ReconciliationIssueStatus.OPEN))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(jpaRepository.save(any(ReconciliationIssueJpaEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_reconciliation_open"));

        ReconciliationIssue result = adapter.open(MERCHANT_ID, Money.of("100.00"), Money.of("150.00"), Instant.now());

        assertThat(result.id()).isEqualTo(existing.getId());
        assertThat(ReconciliationIssueMapper.toEntity(result).getStatus())
                .isEqualTo(ReconciliationIssueStatus.OPEN);
    }
}
