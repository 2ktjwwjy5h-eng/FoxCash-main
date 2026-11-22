package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.PixTransactionJpaEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link PixTransactionMapper} (Spec 127, T5.1): o mapper persiste o
 * traceId de negócio (= id da cobrança — V74) em todo save.
 */
class PixTransactionMapperTest {

    @Test
    void toEntitySetsTraceIdEqualToTransactionId() {
        PixTransaction tx = PixTransaction.restore(UUID.randomUUID(), UUID.randomUUID(),
                Money.of("150.00"), "Cobranca", null, "E0001", "code-psp-1",
                null, null, TransactionStatus.APPROVED,
                Instant.parse("2026-08-16T12:00:00Z"), Instant.parse("2026-08-16T12:01:00Z"),
                Instant.parse("2026-08-16T12:00:30Z"));

        PixTransactionJpaEntity entity = PixTransactionMapper.toEntity(tx);

        assertThat(entity.getTraceId()).isEqualTo(tx.id());
        assertThat(entity.getTraceId()).isEqualTo(tx.traceId());
    }
}
