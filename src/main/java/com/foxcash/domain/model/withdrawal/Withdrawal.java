package com.foxcash.domain.model.withdrawal;

import com.foxcash.domain.model.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Saida (saque) do wallet do lojista (Spec 14, evoluido pela Spec 32/36).
 *
 * <p>A partir da Spec 36 o saque nasce {@code PROCESSING} ({@code processing(...)})
 * — o debito e imediato na solicitacao — e transita via {@code submitToPsp}
 * (grava {@code externalReference}/{@code payoutProvider}), {@code complete(now)}
 * (confirmado pelo webhook de saida/reconciliacao) ou {@code fail(reason, now)}
 * (estorno automatico do debito ao saldo). Imutavel: cada transicao devolve uma
 * nova instancia. A Spec 32 registra o destino usado ({@code destinationId} +
 * {@code destinationKeySnapshot} — snapshot criptografado em repouso), o
 * {@code type} (MERCHANT/ADMINISTRATIVE) e, no saque administrativo, o
 * {@code reason}. {@code REJECTED} (com {@code rejectedAt}) fica reservado para
 * fluxos futuros.
 */
public class Withdrawal {

    private final UUID id;
    private final UUID merchantId;
    private final Money amount;
    private final Money fee;
    private final WithdrawalStatus status;
    private final UUID destinationId;
    private final WithdrawalType type;
    private final String reason;
    private final String destinationKeySnapshot;
    private final Instant createdAt;
    private final Instant completedAt;
    private final Instant rejectedAt;
    private final String externalReference;
    private final String payoutProvider;
    private final String failureReason;
    private final Instant failedAt;

    private Withdrawal(UUID id, UUID merchantId, Money amount, Money fee, WithdrawalStatus status,
                       UUID destinationId, WithdrawalType type, String reason,
                       String destinationKeySnapshot, Instant createdAt,
                       Instant completedAt, Instant rejectedAt,
                       String externalReference, String payoutProvider,
                       String failureReason, Instant failedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.amount = amount;
        this.fee = fee;
        this.status = status;
        this.destinationId = destinationId;
        this.type = type;
        this.reason = reason;
        this.destinationKeySnapshot = destinationKeySnapshot;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.rejectedAt = rejectedAt;
        this.externalReference = externalReference;
        this.payoutProvider = payoutProvider;
        this.failureReason = failureReason;
        this.failedAt = failedAt;
    }

    /**
     * Cria um saque {@code PROCESSING} com o destino/tipo/motivo (Spec 36) —
     * o debito ja ocorreu no wallet; o PSP processa em seguida.
     *
     * @param destinationId          id do destino cadastrado (vendedor) ou nulo (admin)
     * @param destinationKeySnapshot endereço em claro na data do saque (criptografado em repouso)
     * @param type                   MERCHANT ou ADMINISTRATIVE
     * @param reason                 motivo (obrigatório no saque administrativo)
     */
    public static Withdrawal processing(UUID merchantId, Money amount, Money fee,
                                        UUID destinationId, String destinationKeySnapshot,
                                        WithdrawalType type, String reason) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(fee, "fee must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Instant now = Instant.now();
        return new Withdrawal(UUID.randomUUID(), merchantId, amount, fee, WithdrawalStatus.PROCESSING,
                destinationId, type, reason, destinationKeySnapshot, now, null, null,
                null, null, null, null);
    }

    /** PSP aceitou o pagamento: grava a referencia externa + provider (Spec 36). */
    public Withdrawal submitToPsp(String externalReference, String payoutProvider) {
        requireProcessing();
        return new Withdrawal(id, merchantId, amount, fee, status, destinationId, type, reason,
                destinationKeySnapshot, createdAt, completedAt, rejectedAt,
                externalReference, payoutProvider, failureReason, failedAt);
    }

    /** PSP confirmou o pagamento: PROCESSING -> COMPLETED. */
    public Withdrawal complete(Instant now) {
        requireProcessing();
        return new Withdrawal(id, merchantId, amount, fee, WithdrawalStatus.COMPLETED,
                destinationId, type, reason, destinationKeySnapshot, createdAt, now, rejectedAt,
                externalReference, payoutProvider, failureReason, failedAt);
    }

    /**
     * PSP rejeitou o pagamento: PROCESSING -> FAILED (estorno do debito ao saldo).
     * SEC-28 (revisão de segurança 2026-08-11): o motivo é truncado a 255
     * caracteres — a coluna {@code failure_reason} é {@code VARCHAR(255)} e o
     * caller monta {@code "Falha no PSP: " + e.getMessage()}; sem o teto, uma
     * mensagem longa do provedor viraria DataException DENTRO do estorno — o
     * saque ficaria PROCESSING com débito pendurado e a reconciliação em loop.
     */
    public Withdrawal fail(String failureReason, Instant now) {
        requireProcessing();
        return new Withdrawal(id, merchantId, amount, fee, WithdrawalStatus.FAILED,
                destinationId, type, reason, destinationKeySnapshot, createdAt, completedAt, rejectedAt,
                externalReference, payoutProvider, truncateReason(failureReason),
                failedAt != null ? failedAt : now);
    }

    /** Teto da coluna {@code failure_reason} (VARCHAR(255) — Spec 36). */
    private static final int MAX_FAILURE_REASON_LENGTH = 255;

    private static String truncateReason(String failureReason) {
        if (failureReason == null || failureReason.length() <= MAX_FAILURE_REASON_LENGTH) {
            return failureReason;
        }
        return failureReason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    private void requireProcessing() {
        if (status != WithdrawalStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Transicao invalida: saque " + id + " em estado " + status + " (esperado PROCESSING)");
        }
    }

    public static Withdrawal restore(UUID id, UUID merchantId, Money amount, Money fee,
                                     WithdrawalStatus status,
                                     UUID destinationId, WithdrawalType type, String reason,
                                     String destinationKeySnapshot, Instant createdAt,
                                     Instant completedAt, Instant rejectedAt,
                                     String externalReference, String payoutProvider,
                                     String failureReason, Instant failedAt) {
        return new Withdrawal(id, merchantId, amount, fee, status, destinationId, type, reason,
                destinationKeySnapshot, createdAt, completedAt, rejectedAt,
                externalReference, payoutProvider, failureReason, failedAt);
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public Money amount() {
        return amount;
    }

    /** Taxa de saque (Spec 88) — R$ 2,50 por saque do lojista (0.00 no
     *  administrativo). Devolvida integralmente no estorno de FAILED. */
    public Money fee() {
        return fee;
    }

    public WithdrawalStatus status() {
        return status;
    }

    public UUID destinationId() {
        return destinationId;
    }

    public WithdrawalType type() {
        return type;
    }

    public String reason() {
        return reason;
    }

    public String destinationKeySnapshot() {
        return destinationKeySnapshot;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Instant rejectedAt() {
        return rejectedAt;
    }

    public String externalReference() {
        return externalReference;
    }

    public String payoutProvider() {
        return payoutProvider;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant failedAt() {
        return failedAt;
    }
}
