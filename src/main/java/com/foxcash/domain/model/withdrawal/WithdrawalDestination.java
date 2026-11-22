package com.foxcash.domain.model.withdrawal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Destino de saque do lojista (Spec 31) — privado e criptografado em repouso.
 *
 * <p>O valor em claro (chave Pix / dados bancários) só existe na memória no
 * instante da criação/edição; o banco guarda criptografado (AES-256/GCM, via
 * converter na infraestrutura) e a resposta REST devolve apenas a máscara.
 * {@code active} controla se o destino é aceito no saque (Spec 32).
 */
public record WithdrawalDestination(
        UUID id,
        UUID merchantId,
        WithdrawalDestinationType type,
        String value,
        String bankCode,
        String agency,
        String account,
        String accountType,
        String label,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public WithdrawalDestination {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /** Cria um destino ACTIVE (a validação de tipo/formato é feita pelo validador). */
    public static WithdrawalDestination create(UUID merchantId, WithdrawalDestinationType type,
                                               String value, String bankCode, String agency,
                                               String account, String accountType, String label,
                                               Instant now) {
        return new WithdrawalDestination(UUID.randomUUID(), merchantId, type, value,
                bankCode, agency, account, accountType, label, true, now, now);
    }

    public WithdrawalDestination withLabel(String newLabel, Instant now) {
        return new WithdrawalDestination(id, merchantId, type, value, bankCode, agency,
                account, accountType, newLabel, active, createdAt, now);
    }

    public WithdrawalDestination activate(Instant now) {
        return new WithdrawalDestination(id, merchantId, type, value, bankCode, agency,
                account, accountType, label, true, createdAt, now);
    }

    public WithdrawalDestination deactivate(Instant now) {
        return new WithdrawalDestination(id, merchantId, type, value, bankCode, agency,
                account, accountType, label, false, createdAt, now);
    }

    /**
     * Chave bruta do destino (Spec 32): o valor Pix ou os dados bancários
     * concatenados. Usada no snapshot do saque — preserva o endereço usado na
     * data do saque (coluna criptografada em repouso).
     */
    public String snapshotRawKey() {
        return type == WithdrawalDestinationType.BANK_ACCOUNT
                ? bankCode + "|" + agency + "|" + account + "|" + accountType
                : value;
    }

    /** Snapshot persistido do saque (Spec 32): {@code "<TIPO>:<chave bruta>"}. */
    public String snapshotValue() {
        return type.name() + ":" + snapshotRawKey();
    }

    public static WithdrawalDestination restore(UUID id, UUID merchantId, WithdrawalDestinationType type,
                                                String value, String bankCode, String agency,
                                                String account, String accountType, String label,
                                                boolean active, Instant createdAt, Instant updatedAt) {
        return new WithdrawalDestination(id, merchantId, type, value, bankCode, agency,
                account, accountType, label, active, createdAt, updatedAt);
    }
}
