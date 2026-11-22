package com.foxcash.domain.model.withdrawal;

import com.foxcash.domain.model.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes da maquina de estados do {@link Withdrawal} (Spec 32/36): nasce
 * PROCESSING, transita por submitToPsp (referencia externa), complete (COMPLETED
 * com completedAt) e fail (FAILED com motivo/timestamp) — cada transicao com
 * gate PROCESSING (mutation: VoidMethodCall em requireProcessing()).
 */
class WithdrawalTest {

    private static final UUID MERCHANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private static Withdrawal processing() {
        return Withdrawal.processing(MERCHANT, Money.of("40.00"), Money.of("2.50"),
                UUID.randomUUID(), "52998224725", WithdrawalType.MERCHANT, null);
    }

    @Test
    void processingStoresFeeAndTransitionsPreserveIt() {
        Withdrawal w = processing();
        assertThat(w.fee().amount()).isEqualByComparingTo("2.50");

        Withdrawal submitted = w.submitToPsp("PO123", "PAGARME");
        assertThat(submitted.fee().amount()).isEqualByComparingTo("2.50");

        Withdrawal done = submitted.complete(NOW);
        assertThat(done.fee().amount()).isEqualByComparingTo("2.50");

        Withdrawal failed = processing().fail("Falha no PSP", NOW);
        assertThat(failed.fee().amount()).isEqualByComparingTo("2.50");
    }

    @Test
    void restoreKeepsFee() {
        Withdrawal w = Withdrawal.restore(UUID.randomUUID(), MERCHANT, Money.of("40.00"),
                Money.of("2.50"), WithdrawalStatus.PROCESSING, UUID.randomUUID(),
                WithdrawalType.MERCHANT, null, "52998224725", NOW, null, null,
                null, null, null, null);
        assertThat(w.fee().amount()).isEqualByComparingTo("2.50");
    }

    @Test
    void processingCreatesProcessingWithoutPspData() {
        Withdrawal w = processing();

        assertThat(w.status()).isEqualTo(WithdrawalStatus.PROCESSING);
        assertThat(w.externalReference()).isNull();
        assertThat(w.payoutProvider()).isNull();
        assertThat(w.completedAt()).isNull();
        assertThat(w.failureReason()).isNull();
    }

    @Test
    void submitToPspStoresReferenceAndProvider() {
        Withdrawal w = processing();

        Withdrawal submitted = w.submitToPsp("PO123", "PAGARME");

        assertThat(submitted.externalReference()).isEqualTo("PO123");
        assertThat(submitted.payoutProvider()).isEqualTo("PAGARME");
        assertThat(submitted.status()).isEqualTo(WithdrawalStatus.PROCESSING);
    }

    @Test
    void completeSetsStatusAndCompletedAt() {
        Withdrawal w = processing();

        Withdrawal done = w.complete(NOW);

        assertThat(done.status()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(done.completedAt()).isEqualTo(NOW);
    }

    @Test
    void failSetsReasonAndTimestamp() {
        Withdrawal w = processing();

        Withdrawal failed = w.fail("Falha no PSP", NOW);

        assertThat(failed.status()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("Falha no PSP");
        assertThat(failed.failedAt()).isEqualTo(NOW);
    }

    // ---------- SEC-28 (revisão de segurança 2026-08-11): motivo truncado no teto da coluna ----------

    @Test
    void failTruncatesLongReasonToColumnLimit() {
        // SEC-28: "Falha no PSP: " + e.getMessage() pode estourar a coluna
        // failure_reason (VARCHAR(255)) — o truncamento no domínio garante que o
        // estorno nunca quebre por uma mensagem longa do provedor.
        Withdrawal w = processing();

        Withdrawal failed = w.fail("Falha no PSP: " + "x".repeat(500), NOW);

        assertThat(failed.status()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(failed.failureReason()).hasSize(255);
        assertThat(failed.failureReason()).startsWith("Falha no PSP: ");
    }

    @Test
    void failKeepsShortReasonUntouched() {
        Withdrawal w = processing();

        Withdrawal failed = w.fail("Saldo insuficiente", NOW);

        assertThat(failed.failureReason()).isEqualTo("Saldo insuficiente");
    }

    @Test
    void submitToPspAfterCompleteThrows() {
        Withdrawal done = processing().complete(NOW);
        assertThatThrownBy(() -> done.submitToPsp("PO", "PAGARME"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completeTwiceThrows() {
        Withdrawal done = processing().complete(NOW);
        assertThatThrownBy(() -> done.complete(NOW.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failAfterCompleteThrows() {
        Withdrawal done = processing().complete(NOW);
        assertThatThrownBy(() -> done.fail("motivo", NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
