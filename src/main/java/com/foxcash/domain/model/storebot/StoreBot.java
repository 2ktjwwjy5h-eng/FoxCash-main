package com.foxcash.domain.model.storebot;

import com.foxcash.domain.exception.InvalidBotStateException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Registro central de um bot de venda (Spec 74) — a identidade e o ciclo de
 * vida do bot passam a viver no PostgreSQL, com integração real por plataforma
 * (Telegram: getMe/setWebhook; Discord: gerenciador de conexão).
 *
 * <p><b>Segurança do token:</b> o token fica em claro apenas em memória
 * (descriptografado pelo {@code CryptoConverter} na persistência) e é
 * **criptografado em repouso** ({@code v1:} AES-256/GCM). O aggregate nunca
 * expõe o token em resposta HTTP: expõe {@link #tokenMask()} (últimos 4) e
 * {@link #tokenHash()} (lookup por igualdade). O {@code webhook_secret}
 * (32 bytes) segue o mesmo padrão — apenas o {@code webhookSecretHash} é
 * persistido; a URL pública `/webhooks/telegram/{id}/{secret}` carrega o
 * segredo, nunca o id do bot.
 *
 * <p><b>Dois segredos de webhook (auditoria 2026-08-18):</b> o Telegram usa
 * dois segredos independentes — o {@code webhook_secret} na URL (lookup) e o
 * {@code webhookSecretToken} no header {@code X-Telegram-Bot-Api-Secret-Token}
 * (verificaçao de posse, independente da URL). Quem vaza a URL não ganha o
 * header. Ambos são SHA-256 hex persistidos; os valores em claro existem
 * apenas em memória e são (re)gerados pela camada de integração.
 *
 * <p><b>Máquina de estados</b> ({@link BotStatus}): {@code REGISTERING → ACTIVE},
 * {@code ACTIVE ⇄ PAUSED}, {@code REGISTERING/ACTIVE/PAUSED → ERROR} (falha de
 * integração) e qualquer estado → {@code deleted} (soft-delete). Transição
 * inválida → {@link InvalidBotStateException} (409 {@code invalid_bot_state}).
 */
public class StoreBot {

    private static final int MAX_DISPLAY_NAME = 255;
    private static final int MAX_ERROR_MESSAGE = 500;

    private final UUID id;
    private final UUID merchantId;
    private UUID profileId;             // perfil de operação (Spec 122); null = default do lojista
    private final BotPlatform platform;
    private String token;              // em claro apenas em memória; criptografado em repouso
    private final String tokenHash;    // SHA-256 hex — lookup determinístico
    private String webhookSecret;      // em claro apenas em memória; só o hash é persistido
    private String webhookSecretHash;  // SHA-256 hex — lookup no endpoint público
    private String webhookSecretToken;      // em claro apenas em memória; só o hash é persistido
    private String webhookSecretTokenHash;  // SHA-256 hex — verificação do header secret_token
    private String displayName;
    private String username;
    private String applicationId;
    private BotStatus status;
    private String errorMessage;
    private Instant lastHealthCheckAt;
    private Instant deletedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    private StoreBot(UUID id, UUID merchantId, BotPlatform platform, String token, String tokenHash,
                     String webhookSecret, String webhookSecretHash,
                     String webhookSecretToken, String webhookSecretTokenHash,
                     String displayName, String username,
                     String applicationId, BotStatus status, String errorMessage, Instant lastHealthCheckAt,
                     Instant deletedAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.platform = platform;
        this.token = token;
        this.tokenHash = tokenHash;
        this.webhookSecret = webhookSecret;
        this.webhookSecretHash = webhookSecretHash;
        this.webhookSecretToken = webhookSecretToken;
        this.webhookSecretTokenHash = webhookSecretTokenHash;
        this.displayName = displayName;
        this.username = username;
        this.applicationId = applicationId;
        this.status = status;
        this.errorMessage = errorMessage;
        this.lastHealthCheckAt = lastHealthCheckAt;
        this.deletedAt = deletedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Cria um novo registro de bot (status {@code REGISTERING}). Os hashes são
     * computados pela camada de aplicação (SHA-256 hex — padrão do projeto) e
     * recebidos aqui: o domínio não depende de infraestrutura de hash.
     *
     * <p>Overload sem o secret token do header (compatibilidade com callers
     * antigos): delega para a variante completa com token {@code null}.
     */
    public static StoreBot create(UUID merchantId, BotPlatform platform, String token, String tokenHash,
                                  String webhookSecret, String webhookSecretHash, String displayName,
                                  Instant now) {
        return create(merchantId, platform, token, tokenHash, webhookSecret, webhookSecretHash,
                null, null, displayName, now);
    }

    /**
     * Cria um novo registro de bot (status {@code REGISTERING}) com os dois
     * segredos do webhook do Telegram (auditoria 2026-08-18): o {@code secret}
     * da URL e o {@code secretToken} independente do header
     * {@code X-Telegram-Bot-Api-Secret-Token}. Ambos os valores em claro são
     * recebidos aqui; o domínio persiste apenas os hashes.
     */
    public static StoreBot create(UUID merchantId, BotPlatform platform, String token, String tokenHash,
                                  String webhookSecret, String webhookSecretHash,
                                  String webhookSecretToken, String webhookSecretTokenHash,
                                  String displayName, Instant now) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token e obrigatorio no registro do bot");
        }
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException("tokenHash e obrigatorio no registro do bot");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("webhookSecret e obrigatorio no registro do bot");
        }
        if (webhookSecretHash == null || webhookSecretHash.isBlank()) {
            throw new IllegalArgumentException("webhookSecretHash e obrigatorio no registro do bot");
        }
        // Par consistente: ambos null (legado — a integração gera depois) ou
        // ambos presentes. Nunca um só (estado inconsistente de segurança).
        if ((webhookSecretToken == null) != (webhookSecretTokenHash == null)) {
            throw new IllegalArgumentException("webhookSecretToken e webhookSecretTokenHash devem vir juntos");
        }
        validateDisplayName(displayName);
        Objects.requireNonNull(now, "now must not be null");
        return new StoreBot(UUID.randomUUID(), merchantId, platform, token, tokenHash,
                webhookSecret, webhookSecretHash, webhookSecretToken, webhookSecretTokenHash,
                displayName, null, null,
                BotStatus.REGISTERING, null, null, null, now, now);
    }

    /** Restaura do banco (sem validações — o dado persistido é confiável). */
    public static StoreBot restore(UUID id, UUID merchantId, BotPlatform platform, String token,
                                   String tokenHash, String webhookSecretHash, String displayName,
                                   String username, String applicationId, BotStatus status,
                                   String errorMessage, Instant lastHealthCheckAt, Instant deletedAt,
                                   Instant createdAt, Instant updatedAt) {
        return restore(id, merchantId, platform, token, tokenHash, webhookSecretHash, null,
                displayName, username, applicationId, status, errorMessage, lastHealthCheckAt,
                deletedAt, createdAt, updatedAt);
    }

    /**
     * Restaura do banco com o hash do secret token do header (auditoria
     * 2026-08-18). O valor em claro nunca é persistido (só o SHA-256 hex).
     */
    public static StoreBot restore(UUID id, UUID merchantId, BotPlatform platform, String token,
                                   String tokenHash, String webhookSecretHash, String webhookSecretTokenHash,
                                   String displayName, String username, String applicationId, BotStatus status,
                                   String errorMessage, Instant lastHealthCheckAt, Instant deletedAt,
                                   Instant createdAt, Instant updatedAt) {
        return new StoreBot(id, merchantId, platform, token, tokenHash, null, webhookSecretHash,
                null, webhookSecretTokenHash,
                displayName, username, applicationId, status, errorMessage, lastHealthCheckAt,
                deletedAt, createdAt, updatedAt);
    }

    // ---- Máquina de estados (transições inválidas → 409 invalid_bot_state) ----

    /**
     * Ativa o bot: {@code REGISTERING → ACTIVE} (integração concluída),
     * {@code PAUSED → ACTIVE} (resume) e {@code ERROR → ACTIVE} (re-integração
     * pelo job de reconciliação com token válido).
     */
    public void activate(Instant now) {
        requireActive("ativar");
        status = BotStatus.ACTIVE;
        errorMessage = null;
        lastHealthCheckAt = now;
        updatedAt = now;
    }

    /** Pausa o bot: {@code ACTIVE → PAUSED} (integração desativada pelo lojista). */
    public void pause(Instant now) {
        requireActive("pausar");
        if (status != BotStatus.ACTIVE) {
            throw new InvalidBotStateException("Nao e possivel pausar um bot em estado " + status
                    + " (somente ACTIVE pode ser pausado)");
        }
        status = BotStatus.PAUSED;
        updatedAt = now;
    }

    /**
     * Retoma o bot: {@code PAUSED → ACTIVE}. A re-integração (getMe/setWebhook ou
     * reconexão) é responsabilidade da camada de aplicação — aqui só o estado.
     */
    public void resume(Instant now) {
        requireActive("retomar");
        if (status != BotStatus.PAUSED) {
            throw new InvalidBotStateException("Nao e possivel retomar um bot em estado " + status
                    + " (somente PAUSED pode ser retomado)");
        }
        status = BotStatus.ACTIVE;
        errorMessage = null;
        lastHealthCheckAt = now;
        updatedAt = now;
    }

    /** Marca falha de integração/health: {@code REGISTERING/ACTIVE/PAUSED → ERROR}. */
    public void markError(String message, Instant now) {
        requireActive("marcar erro em");
        if (status == BotStatus.ERROR) {
            return; // idempotente — já em ERROR
        }
        if (status == BotStatus.REGISTERING || status == BotStatus.ACTIVE || status == BotStatus.PAUSED) {
            status = BotStatus.ERROR;
            errorMessage = message == null ? null : (message.length() > MAX_ERROR_MESSAGE
                    ? message.substring(0, MAX_ERROR_MESSAGE) : message);
            updatedAt = now;
            return;
        }
        throw new InvalidBotStateException("Nao e possivel marcar erro num bot " + status);
    }

    /** Soft-delete (Spec 74: a linha permanece para auditoria/histórico). */
    public void remove(Instant now) {
        if (deletedAt != null) {
            return; // idempotente — já removido
        }
        deletedAt = now;
        updatedAt = now;
    }

    // ---- Mutações de identidade ----

    /** Atualiza o nome de exibição (PUT — o único campo editável nesta spec). */
    public void updateDisplayName(String displayName, Instant now) {
        validateDisplayName(displayName);
        this.displayName = displayName;
        this.updatedAt = now;
    }

    /** Identidade retornada pela plataforma (getMe do Telegram / @me do Discord). */
    public void setIdentity(String username, String displayName, Instant now) {
        this.username = username;
        if (displayName != null && !displayName.isBlank()) {
            validateDisplayName(displayName);
            this.displayName = displayName;
        }
        this.updatedAt = now;
    }

    /** Discord: Application ID registrado na integração. */
    public void setApplicationId(String applicationId, Instant now) {
        this.applicationId = applicationId;
        this.updatedAt = now;
    }

    /**
     * (Re)define o webhook secret após um reload do banco (só o hash é
     * persistido). A camada de aplicação gera o novo segredo e o hash.
     */
    public void setWebhookSecret(String secret, String secretHash, Instant now) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("webhookSecret nao pode ser vazio");
        }
        if (secretHash == null || secretHash.isBlank()) {
            throw new IllegalArgumentException("webhookSecretHash nao pode ser vazio");
        }
        this.webhookSecret = secret;
        this.webhookSecretHash = secretHash;
        this.updatedAt = now;
    }

    /**
     * (Re)define o secret token do header do Telegram após um reload do banco
     * (só o hash é persistido). A camada de aplicação gera o novo valor e o
     * hash (auditoria 2026-08-18 — token independente da URL).
     */
    public void setWebhookSecretToken(String secretToken, String secretTokenHash, Instant now) {
        if (secretToken == null || secretToken.isBlank()) {
            throw new IllegalArgumentException("webhookSecretToken nao pode ser vazio");
        }
        if (secretTokenHash == null || secretTokenHash.isBlank()) {
            throw new IllegalArgumentException("webhookSecretTokenHash nao pode ser vazio");
        }
        this.webhookSecretToken = secretToken;
        this.webhookSecretTokenHash = secretTokenHash;
        this.updatedAt = now;
    }

    /** Heartbeat da integração (getMe/health check) — atualiza o timestamp. */
    public void refreshHealth(Instant now) {
        this.lastHealthCheckAt = now;
        this.updatedAt = now;
    }

    // ---- Leitura ----

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    /** Perfil de operação do bot (Spec 122 — frota por perfil); null = default. */
    public UUID profileId() {
        return profileId;
    }

    /** Associa o bot a um perfil de operação (Spec 122). */
    public void assignProfile(UUID profileId) {
        this.profileId = profileId;
    }

    public BotPlatform platform() {
        return platform;
    }

    /**
     * Token em claro — APENAS para a camada de integração (nunca em resposta
     * HTTP; o controller usa {@link #tokenMask()}).
     */
    public String token() {
        return token;
    }

    /** SHA-256 hex do token — lookup por igualdade (nunca buscar no ciphertext). */
    public String tokenHash() {
        return tokenHash;
    }

    /** Máscara do token para exibição: últimos 4 caracteres, prefixo {@code ****}. */
    public String tokenMask() {
        if (token == null || token.isBlank()) {
            return "****";
        }
        int len = token.length();
        return len <= 4 ? "****" : "****" + token.substring(len - 4);
    }

    /**
     * Webhook secret em claro — apenas para a integração (setWebhook/URL e o
     * header {@code X-Telegram-Bot-Api-Secret-Token}). {@code null} quando
     * restaurado do banco (só o hash é persistido) — a integração gera um novo.
     */
    public String webhookSecret() {
        return webhookSecret;
    }

    /** SHA-256 hex do webhook secret — lookup no endpoint público. */
    public String webhookSecretHash() {
        return webhookSecretHash;
    }

    /**
     * Secret token do header em claro — apenas para a integração
     * (setWebhook/secret_token). {@code null} quando restaurado do banco (só o
     * hash é persistido) — a integração gera um novo (auditoria 2026-08-18).
     */
    public String webhookSecretToken() {
        return webhookSecretToken;
    }

    /** SHA-256 hex do secret token — verificação do header no endpoint público. */
    public String webhookSecretTokenHash() {
        return webhookSecretTokenHash;
    }

    public String displayName() {
        return displayName;
    }

    public String username() {
        return username;
    }

    public String applicationId() {
        return applicationId;
    }

    public BotStatus status() {
        return status;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Instant lastHealthCheckAt() {
        return lastHealthCheckAt;
    }

    public Instant deletedAt() {
        return deletedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Rejeita mutação num bot soft-deletado (regra comum a todas as transições). */
    private void requireActive(String action) {
        if (deletedAt != null) {
            throw new InvalidBotStateException("Nao e possivel " + action + " um bot removido");
        }
    }

    private static void validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName e obrigatorio no registro do bot");
        }
        if (displayName.length() > MAX_DISPLAY_NAME) {
            throw new IllegalArgumentException("displayName deve ter no maximo " + MAX_DISPLAY_NAME + " caracteres");
        }
    }
}
