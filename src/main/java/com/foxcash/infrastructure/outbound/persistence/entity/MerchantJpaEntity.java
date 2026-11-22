package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.infrastructure.security.CryptoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de lojista (desacoplada do modelo de dominio).
 *
 * <p>Cadastro com nome/e-mail/senha (Spec 39): {@code name} é o nome único e
 * {@code email} o identificador de login (único, normalizado em minúsculas).
 * {@code document} é criptografado em repouso (AES-256/GCM) via
 * {@link CryptoConverter} e é **opcional** (null em novos cadastros — legado
 * preserva); a unicidade é garantida por {@code document_hash} (SHA-256,
 * índice parcial — somente quando presente).
 */
@Entity
@Table(name = "merchants")
public class MerchantJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 255)
    private String email;

    @Convert(converter = CryptoConverter.class)
    @Column(length = 255)
    private String document;

    // 80 chars: DocumentHasher.hmac gera "h1:" + 64 hex (V72 alargou a coluna).
    @Column(name = "document_hash", length = 80)
    private String documentHash;

    @Column(name = "api_key_hash", length = 64)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MerchantStatus status;

    @Column(name = "pricing_fee_per_transaction", nullable = false, precision = 15, scale = 2)
    private BigDecimal pricingFeePerTransaction;

    @Column(name = "pricing_monthly_fee", nullable = false, precision = 15, scale = 2)
    private BigDecimal pricingMonthlyFee;

    @Column(name = "wallet_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal walletBalance;

    @Column(name = "withdrawals_blocked", nullable = false)
    private boolean withdrawalsBlocked;

    // Spec 97 (V60): telefone opcional do lojista para o painel "Dados da Conta".
    // null = não informado; a captura/edição fica na Spec 98 (PUT da config).
    @Column(length = 20)
    private String phone;

    // ---- Spec 98 (V61): config fina por lojista (overrides; null = global) ----
    @Column(name = "pix_fee_type", length = 8)
    private String pixFeeType;

    @Column(name = "pix_fee_fixed", precision = 15, scale = 2)
    private BigDecimal pixFeeFixed;

    @Column(name = "pix_fee_percent", precision = 5, scale = 2)
    private BigDecimal pixFeePercent;

    @Column(name = "pix_fee_threshold", precision = 15, scale = 2)
    private BigDecimal pixFeeThreshold;

    @Column(name = "withdrawal_fee", precision = 15, scale = 2)
    private BigDecimal withdrawalFee;

    @Column(name = "pix_min", precision = 15, scale = 2)
    private BigDecimal pixMin;

    @Column(name = "pix_max", precision = 15, scale = 2)
    private BigDecimal pixMax;

    @Column(name = "withdrawal_min", precision = 15, scale = 2)
    private BigDecimal withdrawalMin;

    @Column(name = "withdrawal_max", precision = 15, scale = 2)
    private BigDecimal withdrawalMax;

    @Column(name = "rate_limit_rps")
    private Integer rateLimitRps;

    @Column(name = "rate_limit_rpm")
    private Integer rateLimitRpm;

    @Column(name = "api_blocked", nullable = false)
    private boolean apiBlocked;

    @Column(nullable = false)
    private boolean honeypot;

    // Spec 128 (V75): gestão automática de risco — true = humano tocou config/
    // status → o RiskScoringEngine não age. Default false (DB DEFAULT false).
    @Column(name = "risk_auto_managed", nullable = false)
    private boolean riskAutoManaged;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MerchantJpaEntity() {
    }

    public MerchantJpaEntity(UUID id, String name, String email, String document, String documentHash,
                             String apiKeyHash, MerchantStatus status, BigDecimal pricingFeePerTransaction,
                             BigDecimal pricingMonthlyFee, BigDecimal walletBalance, boolean withdrawalsBlocked,
                             Instant createdAt) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.document = document;
        this.documentHash = documentHash;
        this.apiKeyHash = apiKeyHash;
        this.status = status;
        this.pricingFeePerTransaction = pricingFeePerTransaction;
        this.pricingMonthlyFee = pricingMonthlyFee;
        this.walletBalance = walletBalance;
        this.withdrawalsBlocked = withdrawalsBlocked;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getDocument() {
        return document;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public MerchantStatus getStatus() {
        return status;
    }

    public BigDecimal getPricingFeePerTransaction() {
        return pricingFeePerTransaction;
    }

    public BigDecimal getPricingMonthlyFee() {
        return pricingMonthlyFee;
    }

    public BigDecimal getWalletBalance() {
        return walletBalance;
    }

    public boolean isWithdrawalsBlocked() {
        return withdrawalsBlocked;
    }

    /** Telefone opcional do lojista (Spec 97 — V60); null = não informado. */
    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    // ---- Spec 98 (V61): config fina por lojista (getters/setters) ----

    public String getPixFeeType() {
        return pixFeeType;
    }

    public void setPixFeeType(String pixFeeType) {
        this.pixFeeType = pixFeeType;
    }

    public BigDecimal getPixFeeFixed() {
        return pixFeeFixed;
    }

    public void setPixFeeFixed(BigDecimal pixFeeFixed) {
        this.pixFeeFixed = pixFeeFixed;
    }

    public BigDecimal getPixFeePercent() {
        return pixFeePercent;
    }

    public void setPixFeePercent(BigDecimal pixFeePercent) {
        this.pixFeePercent = pixFeePercent;
    }

    public BigDecimal getPixFeeThreshold() {
        return pixFeeThreshold;
    }

    public void setPixFeeThreshold(BigDecimal pixFeeThreshold) {
        this.pixFeeThreshold = pixFeeThreshold;
    }

    public BigDecimal getWithdrawalFee() {
        return withdrawalFee;
    }

    public void setWithdrawalFee(BigDecimal withdrawalFee) {
        this.withdrawalFee = withdrawalFee;
    }

    public BigDecimal getPixMin() {
        return pixMin;
    }

    public void setPixMin(BigDecimal pixMin) {
        this.pixMin = pixMin;
    }

    public BigDecimal getPixMax() {
        return pixMax;
    }

    public void setPixMax(BigDecimal pixMax) {
        this.pixMax = pixMax;
    }

    public BigDecimal getWithdrawalMin() {
        return withdrawalMin;
    }

    public void setWithdrawalMin(BigDecimal withdrawalMin) {
        this.withdrawalMin = withdrawalMin;
    }

    public BigDecimal getWithdrawalMax() {
        return withdrawalMax;
    }

    public void setWithdrawalMax(BigDecimal withdrawalMax) {
        this.withdrawalMax = withdrawalMax;
    }

    public Integer getRateLimitRps() {
        return rateLimitRps;
    }

    public void setRateLimitRps(Integer rateLimitRps) {
        this.rateLimitRps = rateLimitRps;
    }

    public Integer getRateLimitRpm() {
        return rateLimitRpm;
    }

    public void setRateLimitRpm(Integer rateLimitRpm) {
        this.rateLimitRpm = rateLimitRpm;
    }

    /** Spec 98: API key do lojista bloqueada (403 no ApiKeyAuthFilter; portal segue). */
    public boolean isApiBlocked() {
        return apiBlocked;
    }

    public void setApiBlocked(boolean apiBlocked) {
        this.apiBlocked = apiBlocked;
    }

    /** Spec 98: lojista em ambiente controlado (provedor trap; saques bloqueados). */
    public boolean isHoneypot() {
        return honeypot;
    }

    public void setHoneypot(boolean honeypot) {
        this.honeypot = honeypot;
    }

    /** Gestão automática de risco (Spec 128 — V75); true = humano tocou → engine não age. */
    public boolean isRiskAutoManaged() {
        return riskAutoManaged;
    }

    public void setRiskAutoManaged(boolean riskAutoManaged) {
        this.riskAutoManaged = riskAutoManaged;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
