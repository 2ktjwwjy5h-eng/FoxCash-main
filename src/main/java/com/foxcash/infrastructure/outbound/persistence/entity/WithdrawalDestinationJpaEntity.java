package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;
import com.foxcash.infrastructure.security.CryptoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de destino de saque (Spec 31) — desacoplada do domínio.
 *
 * <p>Campos sensíveis (value/bank_code/agency/account/account_type) são
 * criptografados em repouso (AES-256/GCM via {@link CryptoConverter}, prefixo
 * {@code v1:}) — nunca em texto claro no banco/backup. {@code status} guarda
 * ACTIVE/INACTIVE.
 */
@Entity
@Table(name = "merchant_withdrawal_destinations")
public class WithdrawalDestinationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WithdrawalDestinationType type;

    @Convert(converter = CryptoConverter.class)
    @Column(length = 255)
    private String value;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "bank_code", length = 255)
    private String bankCode;

    @Convert(converter = CryptoConverter.class)
    @Column(length = 255)
    private String agency;

    @Convert(converter = CryptoConverter.class)
    @Column(length = 255)
    private String account;

    @Convert(converter = CryptoConverter.class)
    @Column(name = "account_type", length = 255)
    private String accountType;

    @Column(length = 60)
    private String label;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WithdrawalDestinationJpaEntity() {
    }

    public WithdrawalDestinationJpaEntity(UUID id, UUID merchantId, WithdrawalDestinationType type,
                                          String value, String bankCode, String agency, String account,
                                          String accountType, String label, String status,
                                          Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.type = type;
        this.value = value;
        this.bankCode = bankCode;
        this.agency = agency;
        this.account = account;
        this.accountType = accountType;
        this.label = label;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public WithdrawalDestinationType getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    public String getBankCode() {
        return bankCode;
    }

    public String getAgency() {
        return agency;
    }

    public String getAccount() {
        return account;
    }

    public String getAccountType() {
        return accountType;
    }

    public String getLabel() {
        return label;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
