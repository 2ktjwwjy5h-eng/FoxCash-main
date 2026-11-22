package com.foxcash.domain.model.transaction;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.plan.Platform;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transacao Pix com maquina de estados de dominio.
 * PENDING -> APPROVED | REJECTED | CANCELLED | FAILED
 * APPROVED -> REFUNDED
 *
 * <p>Gateway somente para o vendedor (decisao de produto): nenhum dado do
 * comprador e capturado — o contato com o cliente e do bot do lojista.
 * {@code endToEndId} (Bacen) e {@code externalReference} coincidem no mock
 * atual e sao mantidos como campos separados por flexibilidade futura.
 */
public class PixTransaction {

    private final UUID id;
    private final UUID merchantId;
    private final Money amount;
    private final String description;
    private final String pixKey;
    private String endToEndId;
    private String externalReference;
    // Spec 91: adquirente que criou a cobrança (ex.: "mock", "pagarme", "asaas",
    // "suitpay") — preenchido via router (roteamento pós-fallback); null = legado/mock
    // sem router. Usado pela reconciliação/webhook para direcionar o provedor.
    private String provider;
    // Spec 124 (fase 2): perfil de operação que decidiu o roteamento da cobrança
    // (pix_transactions.profile_id — V70). Resolvido na criação (bot → perfil do
    // bot; sem bot → default; portal → header validado) e usado pelo filtro
    // rígido do portal. null = transação legada não-escopada (visível só no
    // escopo "sem header" — lojista inteiro).
    private UUID profileId;
    private final String buyerIdHash;
    private String pixQrCode;
    private String pixCopyPaste;
    private final String idempotencyKey;
    private final UUID planId;
    // Spec 75: bot de venda que gerou a cobrança (purchase com botId); null nas
    // cobranças avulsas. Usado para o funil (checkout_started/paid) e métricas.
    private final UUID botId;
    private final Platform platform;
    private final String platformUserId;
    // Provas de entrega (Spec 49+, 2026-08-06): aceite dos termos + IP/dispositivo
    // capturados na compra do plano; copiados para a entrega na aprovação.
    private final Instant termsAcceptedAt;
    private final String buyerIp;
    private final String buyerDevice;
    private TransactionStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant paidAt;

    private PixTransaction(UUID id, UUID merchantId, Money amount, String description, String pixKey,
                           String endToEndId, String externalReference,
                           String pixQrCode, String pixCopyPaste, TransactionStatus status,
                           Instant createdAt, Instant updatedAt, Instant paidAt, String buyerIdHash,
                           String idempotencyKey, UUID planId, UUID botId, Platform platform, String platformUserId,
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

    public static PixTransaction create(UUID merchantId, Money amount, String description, String pixKey) {
        return create(merchantId, amount, description, pixKey, null);
    }

    /**
     * Cria uma transacao com o pseudonimo opaco do comprador (Spec 19):
     * {@code buyerIdHash} e o hash SHA-256 do {@code buyerId} enviado pelo bot
     * do lojista — nenhum dado pessoal e armazenado. Pode ser {@code null}
     * (o bot nao enviou).
     */
    public static PixTransaction create(UUID merchantId, Money amount, String description, String pixKey,
                                        String buyerIdHash) {
        return create(merchantId, amount, description, pixKey, buyerIdHash, null, null, null);
    }

    /**
     * Cria a transacao de compra de plano (Spec 49): além do buyerIdHash opaco,
     * guarda {@code planId} (vínculo da cobrança ao plano) e o destinatário
     * ({@code platform} + {@code platformUserId} — ID do comprador na
     * plataforma, persistido criptografado em repouso) para a entrega
     * automática sobreviver a reinícios do bot.
     */
    public static PixTransaction create(UUID merchantId, Money amount, String description, String pixKey,
                                        String buyerIdHash, UUID planId, Platform platform,
                                        String platformUserId) {
        return create(merchantId, amount, description, pixKey, buyerIdHash, planId, platform,
                platformUserId, null, null, null);
    }

    /**
     * Compra de plano com provas de entrega (Spec 49+): além do destinatário,
     * registra {@code termsAcceptedAt} (aceite dos termos, capturado no request),
     * {@code buyerIp} (IP de origem, mesmo padrão da auditoria) e
     * {@code buyerDevice} (User-Agent) — evidências copiadas para a entrega na
     * aprovação do pagamento. Todos opcionais (null em cobranças avulsas).
     * Compatibilidade: cobrança sem atribuição de bot ({@code botId = null}).
     */
    public static PixTransaction create(UUID merchantId, Money amount, String description, String pixKey,
                                        String buyerIdHash, UUID planId, Platform platform,
                                        String platformUserId, Instant termsAcceptedAt,
                                        String buyerIp, String buyerDevice) {
        return create(merchantId, amount, description, pixKey, buyerIdHash, planId, null, platform,
                platformUserId, termsAcceptedAt, buyerIp, buyerDevice);
    }

    /**
     * Compra de plano com provas de entrega e atribuição de bot (Spec 75):
     * {@code botId} é o bot de venda que gerou a cobrança (usado para o funil
     * {@code checkout_started}/{@code paid} e métricas por bot). {@code null}
     * em cobranças avulsas ou compras sem atribuição de bot.
     */
    public static PixTransaction create(UUID merchantId, Money amount, String description, String pixKey,
                                        String buyerIdHash, UUID planId, UUID botId, Platform platform,
                                        String platformUserId, Instant termsAcceptedAt,
                                        String buyerIp, String buyerDevice) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        if (amount.amount().signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        Instant now = Instant.now();
        return new PixTransaction(UUID.randomUUID(), merchantId, amount, description, pixKey,
                null, null, null, null, TransactionStatus.PENDING, now, now, null, buyerIdHash,
                null, planId, botId, platform, platformUserId, termsAcceptedAt, buyerIp, buyerDevice);
    }

    public static PixTransaction restore(UUID id, UUID merchantId, Money amount, String description, String pixKey,
                                         String endToEndId, String externalReference,
                                         String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                         Instant createdAt, Instant updatedAt, Instant paidAt) {
        return restore(id, merchantId, amount, description, pixKey, endToEndId, externalReference,
                pixQrCode, pixCopyPaste, status, createdAt, updatedAt, paidAt, null);
    }

    public static PixTransaction restore(UUID id, UUID merchantId, Money amount, String description, String pixKey,
                                         String endToEndId, String externalReference,
                                         String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                         Instant createdAt, Instant updatedAt, Instant paidAt, String buyerIdHash) {
        return restore(id, merchantId, amount, description, pixKey, endToEndId, externalReference,
                pixQrCode, pixCopyPaste, status, createdAt, updatedAt, paidAt, buyerIdHash, null);
    }

    public static PixTransaction restore(UUID id, UUID merchantId, Money amount, String description, String pixKey,
                                         String endToEndId, String externalReference,
                                         String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                         Instant createdAt, Instant updatedAt, Instant paidAt, String buyerIdHash,
                                         String idempotencyKey) {
        return restore(id, merchantId, amount, description, pixKey, endToEndId, externalReference,
                pixQrCode, pixCopyPaste, status, createdAt, updatedAt, paidAt, buyerIdHash,
                idempotencyKey, null, null, null);
    }

    public static PixTransaction restore(UUID id, UUID merchantId, Money amount, String description, String pixKey,
                                         String endToEndId, String externalReference,
                                         String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                         Instant createdAt, Instant updatedAt, Instant paidAt, String buyerIdHash,
                                         String idempotencyKey, UUID planId, Platform platform,
                                         String platformUserId) {
        return restore(id, merchantId, amount, description, pixKey, endToEndId, externalReference,
                pixQrCode, pixCopyPaste, status, createdAt, updatedAt, paidAt, buyerIdHash,
                idempotencyKey, planId, platform, platformUserId, null, null, null);
    }

    public static PixTransaction restore(UUID id, UUID merchantId, Money amount, String description, String pixKey,
                                         String endToEndId, String externalReference,
                                         String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                         Instant createdAt, Instant updatedAt, Instant paidAt, String buyerIdHash,
                                         String idempotencyKey, UUID planId, Platform platform,
                                         String platformUserId, Instant termsAcceptedAt,
                                         String buyerIp, String buyerDevice) {
        return restore(id, merchantId, amount, description, pixKey, endToEndId, externalReference,
                pixQrCode, pixCopyPaste, status, createdAt, updatedAt, paidAt, buyerIdHash,
                idempotencyKey, planId, null, platform, platformUserId, termsAcceptedAt,
                buyerIp, buyerDevice);
    }

    public static PixTransaction restore(UUID id, UUID merchantId, Money amount, String description, String pixKey,
                                         String endToEndId, String externalReference,
                                         String pixQrCode, String pixCopyPaste, TransactionStatus status,
                                         Instant createdAt, Instant updatedAt, Instant paidAt, String buyerIdHash,
                                         String idempotencyKey, UUID planId, UUID botId, Platform platform,
                                         String platformUserId, Instant termsAcceptedAt,
                                         String buyerIp, String buyerDevice) {
        return new PixTransaction(id, merchantId, amount, description, pixKey,
                endToEndId, externalReference, pixQrCode, pixCopyPaste,
                status, createdAt, updatedAt, paidAt, buyerIdHash, idempotencyKey,
                planId, botId, platform, platformUserId, termsAcceptedAt, buyerIp, buyerDevice);
    }

    /**
     * Atribui a chave de idempotência (Spec 28) — usada pela persistência
     * antecipada: a transação PENDING nasce sem chave e a recebe ANTES da
     * chamada ao PSP, em transação própria. Retorna uma NOVA instância com a
     * chave — o chamador reassina {@code tx = persistPending(tx, key)} para que
     * os saves seguintes (attach/fail) preservem a coluna.
     */
    public PixTransaction withIdempotencyKey(String idempotencyKey) {
        PixTransaction copy = new PixTransaction(id, merchantId, amount, description, pixKey,
                endToEndId, externalReference, pixQrCode, pixCopyPaste,
                status, createdAt, updatedAt, paidAt, buyerIdHash, idempotencyKey,
                planId, botId, platform, platformUserId, termsAcceptedAt, buyerIp, buyerDevice);
        // Spec 124: o perfil de operação é campo mutável (attachProfile) — a cópia
        // precisa preservá-lo ou o persistPending salvaria a cobrança sem profile_id
        // (a "rota viva" e o filtro rígido do portal perderiam o vínculo).
        copy.profileId = this.profileId;
        return copy;
    }

    /**
     * Vincula a referencia do provedor e o payload Pix (QR Code base64 e
     * Copia e Cola) retornados pelo PSP apos a criacao da cobranca.
     */
    public void attachCharge(String externalReference, String pixQrCode, String pixCopyPaste) {
        this.endToEndId = externalReference;
        this.externalReference = externalReference;
        this.pixQrCode = pixQrCode;
        this.pixCopyPaste = pixCopyPaste;
        touch();
    }

    /**
     * Spec 91: anexa a cobrança junto com o adquirente que a criou (o router
     * preenche o provider no resultado; null com adapter direto).
     */
    public void attachCharge(String externalReference, String pixQrCode, String pixCopyPaste,
                             String provider) {
        attachCharge(externalReference, pixQrCode, pixCopyPaste);
        this.provider = provider;
    }

    /**
     * Spec 91: registra o adquirente da cobrança (restore do banco — SEM touch:
     * o updatedAt não reflete metadado de origem).
     */
    public void attachProvider(String provider) {
        this.provider = provider;
    }

    /**
     * Spec 124: registra o perfil de operação da transação (na criação — antes
     * da persistência da PENDING — e no restore do banco). SEM touch: metadado
     * de escopo, não de estado.
     */
    public void attachProfile(UUID profileId) {
        this.profileId = profileId;
    }

    public void approve() {
        requireStatus(TransactionStatus.PENDING);
        this.status = TransactionStatus.APPROVED;
        this.paidAt = Instant.now();
        touch();
    }

    public void reject() {
        requireStatus(TransactionStatus.PENDING);
        this.status = TransactionStatus.REJECTED;
        touch();
    }

    public void refund() {
        requireStatus(TransactionStatus.APPROVED);
        this.status = TransactionStatus.REFUNDED;
        touch();
    }

    /**
     * Erro real do PSP na criação da cobrança (Spec 28): a PENDING vira
     * {@code FAILED} — a chave de idempotência já produziu resultado e o retry
     * com a mesma chave responde 409 {@code idempotency_conflict}.
     */
    public void fail() {
        requireStatus(TransactionStatus.PENDING);
        this.status = TransactionStatus.FAILED;
        touch();
    }

    private void requireStatus(TransactionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Transaction " + id + " is " + status + ", expected " + expected);
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    /**
     * traceId de negócio (Spec 127) — É o próprio {@code id} da cobrança:
     * estável do nascimento à morte (a idempotência da Spec 28 garante que o
     * retry reusa a MESMA transação) e já presente em todas as fontes da jornada
     * (activity entityId, ledger referenceId, outbox data.transactionId). A
     * coluna {@code pix_transactions.trace_id} (V74, backfill = id) existe para
     * o vínculo canônico explícito e a correlação de logs (MDC do worker).
     */
    public UUID traceId() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public Money amount() {
        return amount;
    }

    public String description() {
        return description;
    }

    public String pixKey() {
        return pixKey;
    }

    public String endToEndId() {
        return endToEndId;
    }

    public String externalReference() {
        return externalReference;
    }

    /** Adquirente que criou a cobrança (Spec 91); null sem router (legado/mock). */
    public String provider() {
        return provider;
    }

    /** Perfil de operação da transação (Spec 124 — V70); null = legado não-escopado. */
    public UUID profileId() {
        return profileId;
    }

    /** Hash SHA-256 do buyerId opaco (Spec 19); null se o bot nao enviou. */
    public String buyerIdHash() {
        return buyerIdHash;
    }

    /** Chave de idempotência do cliente (Spec 28); null nas transações legadas. */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /** Plano comprado (Spec 49); null nas cobranças avulsas. */
    public UUID planId() {
        return planId;
    }

    /** Bot de venda que gerou a cobrança (Spec 75); null sem atribuição de bot. */
    public UUID botId() {
        return botId;
    }

    /** Plataforma do comprador (Spec 49); null nas cobranças avulsas. */
    public Platform platform() {
        return platform;
    }

    /** ID do comprador na plataforma (Spec 49); criptografado em repouso. */
    public String platformUserId() {
        return platformUserId;
    }

    public String pixQrCode() {
        return pixQrCode;
    }

    public String pixCopyPaste() {
        return pixCopyPaste;
    }

    public TransactionStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant paidAt() {
        return paidAt;
    }

    /** Aceite dos termos registrado na compra do plano (Spec 49+); null se não aceitou. */
    public Instant termsAcceptedAt() {
        return termsAcceptedAt;
    }

    /** IP de origem do comprador na compra (prova de entrega); null se não capturado. */
    public String buyerIp() {
        return buyerIp;
    }

    /** User-Agent do dispositivo do comprador na compra (prova de entrega); null se não capturado. */
    public String buyerDevice() {
        return buyerDevice;
    }
}
