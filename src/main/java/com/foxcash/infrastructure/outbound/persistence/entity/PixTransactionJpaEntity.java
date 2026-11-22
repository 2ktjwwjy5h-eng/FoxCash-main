package com.foxcash.infrastructure.outbound.persistence.entity;

import com.foxcash.domain.model.plan.Platform;
import com.foxcash.domain.model.transaction.TransactionStatus;
import com.foxcash.infrastructure.security.CryptoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade JPA de transacao Pix (desacoplada do modelo de dominio).
 *
 * <p>Gateway somente para o vendedor (decisao de produto 2026-08-04): nenhum
 * dado do comprador e capturado — o contato com o cliente e do bot do lojista.
 * {@code externalReference} e a referencia do PSP usada na confirmacao via
 * webhook (endToEndId no mock).
 */
@Entity
@Table(name = "pix_transactions",
        indexes = {
                @Index(name = "idx_pix_transactions_merchant", columnList = "merchant_id, created_at")
        })
public class PixTransactionJpaEntity {

    @Id
    private UUID id;

    // Spec 127 (V74): traceId de negócio = id da cobrança (backfill = id para
    // legadas). Vínculo canônico explícito p/ correlação de logs (MDC do worker).
    // Sem índice: não é chave de lookup (o trace resolve por id/ref/key).
    @Column(name = "trace_id", nullable = false)
    private UUID traceId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(length = 255)
    private String description;

    // B-03 (auditoria 2026-08-05): pix_key criptografada em repouso (AES-256/GCM,
    // padrão dos demais dados sensíveis). O backfill dos registros antigos (sem
    // prefixo v1:) é feito no boot pelo PixKeyEncryptionRunner — sem lookup por
    // pix_key em nenhum fluxo (verificado; IV aleatório inviabiliza igualdade).
    @Convert(converter = CryptoConverter.class)
    @Column(name = "pix_key", length = 255)
    private String pixKey;

    // Unique parcial garantido pela migration (uq_pix_transactions_end_to_end)
    @Column(name = "end_to_end_id", length = 64)
    private String endToEndId;

    @Column(name = "external_reference", length = 255)
    private String externalReference;

    // Spec 91: adquirente que criou a cobrança (V58) — ex.: "mock", "pagarme",
    // "asaas", "suitpay"; null = legado/mock sem router. Direciona a
    // reconciliação/webhook por provedor.
    @Column(name = "provider", length = 16)
    private String provider;

    // Spec 124 (V70): perfil de operação da transação (filtro rígido do portal +
    // rota viva). null = legado não-escopado (visível só no escopo "sem header").
    @Column(name = "profile_id")
    private UUID profileId;

    @Column(name = "buyer_id_hash", length = 64)
    private String buyerIdHash;

    // Unique parcial por lojista garantido pela migration (uq_pix_transactions_idempotency, Spec 28)
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    // Spec 49: compra de plano — vínculo da cobrança ao plano + destinatário.
    @Column(name = "plan_id")
    private UUID planId;

    // Spec 75: bot de venda que gerou a cobrança (purchase com botId); null nas
    // cobranças avulsas. Usado para o funil e métricas por bot.
    @Column(name = "bot_id")
    private UUID botId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Platform platform;

    // ID do comprador na plataforma (Discord/Telegram) — criptografado em
    // repouso (decisão 2026-08-05: backend = única fonte de verdade da entrega).
    @Convert(converter = CryptoConverter.class)
    @Column(name = "platform_user_id", length = 255)
    private String platformUserId;

    // Provas de entrega (Spec 49+, 2026-08-06): aceite dos termos + IP/dispositivo
    // capturados na compra do plano; copiados para a entrega na aprovação.
    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "buyer_ip", length = 45)
    private String buyerIp;

    @Column(name = "buyer_device", length = 255)
    private String buyerDevice;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "pix_qr_code")
    private String pixQrCode;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "pix_copy_paste")
    private String pixCopyPaste;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected PixTransactionJpaEntity() {
    }

    public PixTransactionJpaEntity(UUID id, UUID merchantId, BigDecimal amount, String description,
                                   String pixKey, String endToEndId, String externalReference, String buyerIdHash,
                                   String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                   Instant createdAt, Instant updatedAt, Instant paidAt) {
        this(id, merchantId, amount, description, pixKey, endToEndId, externalReference, buyerIdHash,
                pixQrCode, pixCopyPaste, status, createdAt, updatedAt, paidAt, null);
    }

    public PixTransactionJpaEntity(UUID id, UUID merchantId, BigDecimal amount, String description,
                                   String pixKey, String endToEndId, String externalReference, String buyerIdHash,
                                   String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                   Instant createdAt, Instant updatedAt, Instant paidAt, String idempotencyKey) {
        this(id, merchantId, amount, description, pixKey, endToEndId, externalReference, buyerIdHash,
                pixQrCode, pixCopyPaste, status, createdAt, updatedAt, paidAt, idempotencyKey,
                null, null, null);
    }

    public PixTransactionJpaEntity(UUID id, UUID merchantId, BigDecimal amount, String description,
                                   String pixKey, String endToEndId, String externalReference, String buyerIdHash,
                                   String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                   Instant createdAt, Instant updatedAt, Instant paidAt, String idempotencyKey,
                                   UUID planId, Platform platform, String platformUserId) {
        this(id, merchantId, amount, description, pixKey, endToEndId, externalReference, buyerIdHash,
                pixQrCode, pixCopyPaste, status, createdAt, updatedAt, paidAt, idempotencyKey,
                planId, platform, platformUserId, null, null, null);
    }

    public PixTransactionJpaEntity(UUID id, UUID merchantId, BigDecimal amount, String description,
                                   String pixKey, String endToEndId, String externalReference, String buyerIdHash,
                                   String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                   Instant createdAt, Instant updatedAt, Instant paidAt, String idempotencyKey,
                                   UUID planId, Platform platform, String platformUserId,
                                   Instant termsAcceptedAt, String buyerIp, String buyerDevice) {
        this(id, merchantId, amount, description, pixKey, endToEndId, externalReference, buyerIdHash,
                pixQrCode, pixCopyPaste, status, createdAt, updatedAt, paidAt, idempotencyKey,
                planId, null, platform, platformUserId, termsAcceptedAt, buyerIp, buyerDevice);
    }

    public PixTransactionJpaEntity(UUID id, UUID merchantId, BigDecimal amount, String description,
                                   String pixKey, String endToEndId, String externalReference, String buyerIdHash,
                                   String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                   Instant createdAt, Instant updatedAt, Instant paidAt, String idempotencyKey,
                                   UUID planId, UUID botId, Platform platform, String platformUserId,
                                   Instant termsAcceptedAt, String buyerIp, String buyerDevice) {
        this.id = id;
        this.merchantId = merchantId;
        this.amount = amount;
        this.description = description;
        this.pixKey = pixKey;
        this.endToEndId = endToEndId;
        this.externalReference = externalReference;
        this.buyerIdHash = buyerIdHash;
        this.pixQrCode = pixQrCode;
        this.pixCopyPaste = pixCopyPaste;
        this.idempotencyKey = idempotencyKey;
        this.planId = planId;
        this.botId = botId;
        this.platform = platform;
        this.platformUserId = platformUserId;
        this.termsAcceptedAt = termsAcceptedAt;
        this.buyerIp = buyerIp;
        this.buyerDevice = buyerDevice;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.paidAt = paidAt;
    }

    public UUID getId() {
        return id;
    }

    /** traceId de negócio (Spec 127 — V74); setado pelo mapper (= id). */
    public UUID getTraceId() {
        return traceId;
    }

    public void setTraceId(UUID traceId) {
        this.traceId = traceId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public String getPixKey() {
        return pixKey;
    }

    public String getEndToEndId() {
        return endToEndId;
    }

    public String getExternalReference() {
        return externalReference;
    }

    /** Adquirente que criou a cobrança (Spec 91 — V58); null = legado/mock. */
    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    /** Perfil de operação da transação (Spec 124 — V70); null = legado não-escopado. */
    public UUID getProfileId() {
        return profileId;
    }

    public void setProfileId(UUID profileId) {
        this.profileId = profileId;
    }

    public String getBuyerIdHash() {
        return buyerIdHash;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPixQrCode() {
        return pixQrCode;
    }

    public UUID getPlanId() {
        return planId;
    }

    public UUID getBotId() {
        return botId;
    }

    public Platform getPlatform() {
        return platform;
    }

    public String getPlatformUserId() {
        return platformUserId;
    }

    public Instant getTermsAcceptedAt() {
        return termsAcceptedAt;
    }

    public String getBuyerIp() {
        return buyerIp;
    }

    public String getBuyerDevice() {
        return buyerDevice;
    }

    public String getPixCopyPaste() {
        return pixCopyPaste;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
