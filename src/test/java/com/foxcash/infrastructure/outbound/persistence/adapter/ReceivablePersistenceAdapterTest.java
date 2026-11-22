package com.foxcash.infrastructure.outbound.persistence.adapter;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.billing.Receivable;
import com.foxcash.domain.model.billing.ReceivableOrigin;
import com.foxcash.domain.model.billing.ReceivableStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.ReceivableJpaEntity;
import com.foxcash.infrastructure.outbound.persistence.repository.ReceivableJpaRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link ReceivablePersistenceAdapter} (Spec 33) — em especial o
 * {@code upsertOpen} da divida formal de fatura: o invariante do dominio
 * {@code remaining() = amount - paidAmount} deve refletir o saldo devedor
 * atual apos cada re-cobranca parcial, sem nunca ficar negativo.
 *
 * <p>Regressao do bug contabil (2026-08-05): o {@code amount} era reduzido
 * junto com o {@code paidAmount} na re-cobranca parcial, tornando
 * {@code remaining()} negativo (ex.: fatura R$100, cobrancas de R$60+R$30 →
 * receivable amount=10/paid=30 → remaining=-20) e corrompendo a consulta do
 * master e o write-off (perda negativa no ledger).
 */
class ReceivablePersistenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-05T02:30:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID REFERENCE_ID = UUID.randomUUID();
    private static final List<ReceivableStatus> OPEN_STATUSES =
            List.of(ReceivableStatus.OPEN, ReceivableStatus.PARTIAL);

    private final ReceivableJpaRepository jpaRepository = mock(ReceivableJpaRepository.class);
    private final ReceivablePersistenceAdapter adapter = new ReceivablePersistenceAdapter(jpaRepository);

    private ReceivableJpaEntity entity(String amount, String paid, ReceivableStatus status) {
        return new ReceivableJpaEntity(UUID.randomUUID(), MERCHANT_ID, ReceivableOrigin.INVOICE,
                REFERENCE_ID, new BigDecimal(amount), new BigDecimal(paid), status, NOW, null, NOW);
    }

    private void mockExisting(ReceivableJpaEntity existing) {
        when(jpaRepository.findByMerchantIdAndOriginAndReferenceIdAndStatusIn(
                MERCHANT_ID, ReceivableOrigin.INVOICE, REFERENCE_ID, OPEN_STATUSES))
                .thenReturn(Optional.of(existing));
        when(jpaRepository.save(any(ReceivableJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void upsertOpenCreatesNewReceivableWhenNoneExists() {
        when(jpaRepository.findByMerchantIdAndOriginAndReferenceIdAndStatusIn(
                MERCHANT_ID, ReceivableOrigin.INVOICE, REFERENCE_ID, OPEN_STATUSES))
                .thenReturn(Optional.empty());
        when(jpaRepository.save(any(ReceivableJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Receivable result = adapter.upsertOpen(MERCHANT_ID, ReceivableOrigin.INVOICE, REFERENCE_ID,
                Money.of("60.00"), NOW);

        assertThat(result.status()).isEqualTo(ReceivableStatus.OPEN);
        assertThat(result.amount().amount()).isEqualByComparingTo("60.00");
        assertThat(result.paidAmount().amount()).isEqualByComparingTo("0.00");
        assertThat(result.remaining().amount()).isEqualByComparingTo("60.00");
    }

    @Test
    void upsertOpenAccumulatesPartialPaymentKeepingOriginalAmount() {
        // Fatura R$100; primeira cobranca R$60 -> receivable aberto em 40.
        // Segunda cobranca de R$30 -> saldo devedor 10: paid_amount=30,
        // amount permanece 40 e remaining()=10 (antes do fix: remaining=-20).
        mockExisting(entity("40.00", "0.00", ReceivableStatus.OPEN));

        Receivable result = adapter.upsertOpen(MERCHANT_ID, ReceivableOrigin.INVOICE, REFERENCE_ID,
                Money.of("10.00"), NOW);

        assertThat(result.status()).isEqualTo(ReceivableStatus.PARTIAL);
        assertThat(result.amount().amount()).isEqualByComparingTo("40.00");
        assertThat(result.paidAmount().amount()).isEqualByComparingTo("30.00");
        assertThat(result.remaining().amount()).isEqualByComparingTo("10.00");
        assertThat(result.remaining().amount().signum()).isPositive();
        assertThat(result.isFullyPaid()).isFalse();
    }

    @Test
    void upsertOpenAcrossMultipleCollectionsKeepsRemainingEqualToDebt() {
        // Tres etapas: 40 -> 10 -> 5. paid_amount acumula 35; amount fica 40;
        // remaining()=5 (igual ao saldo devedor informado).
        mockExisting(entity("40.00", "30.00", ReceivableStatus.PARTIAL));

        Receivable result = adapter.upsertOpen(MERCHANT_ID, ReceivableOrigin.INVOICE, REFERENCE_ID,
                Money.of("5.00"), NOW);

        assertThat(result.amount().amount()).isEqualByComparingTo("40.00");
        assertThat(result.paidAmount().amount()).isEqualByComparingTo("35.00");
        assertThat(result.remaining().amount()).isEqualByComparingTo("5.00");
        assertThat(result.status()).isEqualTo(ReceivableStatus.PARTIAL);
        assertThat(result.isFullyPaid()).isFalse();
    }

    @Test
    void upsertOpenWithoutProgressIsNoop() {
        // Mesmo saldo devedor (10): sem novo pagamento -> nenhuma mudanca de
        // estado nem de valores (idempotente).
        mockExisting(entity("40.00", "30.00", ReceivableStatus.PARTIAL));

        Receivable result = adapter.upsertOpen(MERCHANT_ID, ReceivableOrigin.INVOICE, REFERENCE_ID,
                Money.of("10.00"), NOW);

        assertThat(result.status()).isEqualTo(ReceivableStatus.PARTIAL);
        assertThat(result.amount().amount()).isEqualByComparingTo("40.00");
        assertThat(result.paidAmount().amount()).isEqualByComparingTo("30.00");
        assertThat(result.remaining().amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void markPaidIfOpenSettlesToOriginalAmount() {
        // Fatura quitada depois de pagamentos parciais: receivable (amount=40,
        // paid=30) vai a PAID com paid_amount = amount (dívida total quitada).
        ReceivableJpaEntity existing = entity("40.00", "30.00", ReceivableStatus.PARTIAL);
        when(jpaRepository.findByMerchantIdAndOriginAndReferenceIdAndStatusIn(
                MERCHANT_ID, ReceivableOrigin.INVOICE, REFERENCE_ID, OPEN_STATUSES))
                .thenReturn(Optional.of(existing));
        when(jpaRepository.save(any(ReceivableJpaEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        adapter.markPaidIfOpen(MERCHANT_ID, ReceivableOrigin.INVOICE, REFERENCE_ID, NOW);

        assertThat(existing.getStatus()).isEqualTo(ReceivableStatus.PAID);
        assertThat(existing.getPaidAmount()).isEqualByComparingTo("40.00");
        assertThat(existing.getPaidAt()).isEqualTo(NOW);
    }
}
