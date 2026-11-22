package com.foxcash.domain.model.transaction;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.plan.Platform;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes da maquina de estados da {@link PixTransaction} (Spec 19/28/29):
 * criacao (PENDING, amount positivo), transicoes approve/reject/refund/fail
 * (com gate de status) e attachCharge (payload do PSP). Usa restore com
 * timestamps fixos para asserter o bump de {@code updatedAt} (mutation:
 * VoidMethodCall em touch()).
 */
class PixTransactionTest {

    private static final UUID MERCHANT = UUID.randomUUID();
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    private static PixTransaction pending() {
        return PixTransaction.restore(UUID.randomUUID(), MERCHANT, Money.of("50.00"), "venda",
                null, null, null, null, null, TransactionStatus.PENDING, OLD, OLD, null);
    }

    private static PixTransaction approved() {
        return PixTransaction.restore(UUID.randomUUID(), MERCHANT, Money.of("50.00"), "venda",
                null, null, null, null, null, TransactionStatus.APPROVED, OLD, OLD, OLD);
    }

    @Test
    void createStartsPendingWithoutPspData() {
        PixTransaction tx = PixTransaction.create(MERCHANT, Money.of("50.00"), "venda", "chave@mail.com");

        assertThat(tx.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.externalReference()).isNull();
        assertThat(tx.pixQrCode()).isNull();
        assertThat(tx.pixCopyPaste()).isNull();
        assertThat(tx.paidAt()).isNull();
        assertThat(tx.updatedAt()).isNotNull();
        // Getters do núcleo (mutation: merchantId/createdAt → null, description/pixKey → "").
        assertThat(tx.merchantId()).isEqualTo(MERCHANT);
        assertThat(tx.description()).isEqualTo("venda");
        assertThat(tx.pixKey()).isEqualTo("chave@mail.com");
        assertThat(tx.createdAt()).isNotNull();
    }

    @Test
    void createRejectsNonPositiveAmount() {
        // Boundary do amount (mutation: signum() <= 0 <-> < 0): zero é rejeitado.
        assertThatThrownBy(() -> PixTransaction.create(MERCHANT, Money.of("0"), "venda", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> PixTransaction.create(MERCHANT, Money.of("-1"), "venda", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void approveMarksApprovedWithPaidAtAndBumpsUpdatedAt() {
        PixTransaction tx = pending();

        tx.approve();

        assertThat(tx.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(tx.paidAt()).isNotNull();
        assertThat(tx.updatedAt()).isAfter(OLD); // touch() executado
    }

    @Test
    void rejectMarksRejectedAndBumpsUpdatedAt() {
        PixTransaction tx = pending();

        tx.reject();

        assertThat(tx.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(tx.updatedAt()).isAfter(OLD); // touch() executado
    }

    @Test
    void refundTransitionsFromApproved() {
        PixTransaction tx = approved();

        tx.refund();

        assertThat(tx.status()).isEqualTo(TransactionStatus.REFUNDED);
        assertThat(tx.updatedAt()).isAfter(OLD); // touch() executado
    }

    @Test
    void failMarksFailedAndBumpsUpdatedAt() {
        PixTransaction tx = pending();

        tx.fail();

        assertThat(tx.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.updatedAt()).isAfter(OLD); // touch() executado
    }

    @Test
    void createWithDeliveryProofsExposesGetterValues() {
        // Spec 49+: provas de entrega (termsAcceptedAt/buyerIp/buyerDevice) — os
        // getters devolvem os valores capturados na compra do plano (mutation:
        // EmptyObjectReturnVals trocaria por "", NullReturnVals por null).
        Instant terms = Instant.parse("2026-08-06T12:00:00Z");
        PixTransaction tx = PixTransaction.create(MERCHANT, Money.of("50.00"), "plano", null,
                null, UUID.randomUUID(), Platform.DISCORD, "user-1", terms,
                "200.201.202.203", "FoxBot/1.0");

        assertThat(tx.termsAcceptedAt()).isEqualTo(terms);
        assertThat(tx.buyerIp()).isEqualTo("200.201.202.203");
        assertThat(tx.buyerDevice()).isEqualTo("FoxBot/1.0");
    }

    @Test
    void withIdempotencyKeyExposesTheKey() {
        // Mutation (EmptyObjectReturnVals): idempotencyKey() trocado por "".
        PixTransaction tx = pending().withIdempotencyKey("cob-key-7");

        assertThat(tx.idempotencyKey()).isEqualTo("cob-key-7");
    }

    @Test
    void withIdempotencyKeyPreservesProfileId() {
        // Spec 124 (regressão E2E): o perfil de operação é campo mutável
        // (attachProfile); a cópia do withIdempotencyKey precisa preservá-lo ou
        // o persistPending salvaria a cobrança sem profile_id (rota viva + filtro
        // rígido do portal perderiam o vínculo).
        UUID profileId = UUID.randomUUID();
        PixTransaction tx = pending();
        tx.attachProfile(profileId);

        PixTransaction copy = tx.withIdempotencyKey("cob-key-8");

        assertThat(copy.profileId()).isEqualTo(profileId);
    }

    @Test
    void attachChargeStoresPspPayloadAndBumpsUpdatedAt() {
        PixTransaction tx = pending();

        tx.attachCharge("E123", "qr-base64", "000201...");

        assertThat(tx.externalReference()).isEqualTo("E123");
        assertThat(tx.endToEndId()).isEqualTo("E123");
        assertThat(tx.pixQrCode()).isEqualTo("qr-base64");
        assertThat(tx.pixCopyPaste()).isEqualTo("000201...");
        assertThat(tx.updatedAt()).isAfter(OLD); // touch() executado
    }

    @Test
    void approveFromApprovedThrows() {
        PixTransaction tx = approved();
        assertThatThrownBy(tx::approve).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectFromApprovedThrows() {
        PixTransaction tx = approved();
        assertThatThrownBy(tx::reject).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refundFromPendingThrows() {
        PixTransaction tx = pending();
        assertThatThrownBy(tx::refund).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failFromApprovedThrows() {
        PixTransaction tx = approved();
        assertThatThrownBy(tx::fail).isInstanceOf(IllegalStateException.class);
    }
}
