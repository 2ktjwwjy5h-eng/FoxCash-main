package com.foxcash.domain.model.apikey;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Chave de API gerenciável do lojista (Spec 24) — evolução da chave única do
 * cadastro (Spec 12). O segredo em claro NUNCA é persistido: apenas o hash
 * SHA-256 ({@code keyHash}) e um prefixo curto (ex.: {@code mch_a1b2c3}) para
 * identificação no portal. A chave pode ser revogada e tem escopos que
 * restringem as rotas acessíveis.
 */
public class MerchantApiKey {

    /** Número máximo de chaves ativas por lojista (critério de aceite). */
    public static final int MAX_ACTIVE_KEYS = 5;

    /** Prefixo das chaves geradas (mesmo padrão do cadastro, Spec 12). */
    public static final String KEY_PREFIX = "mch_";

    /** Quantos caracteres hex do segredo aparecem no prefixo exibido. */
    private static final int PREFIX_HEX_LENGTH = 6;

    private final UUID id;
    private final UUID merchantId;
    private final String label;
    private final String prefix;
    private final String keyHash;
    private ApiKeyStatus status;
    private final Set<ApiKeyScope> scopes;
    private Instant lastUsedAt;
    private final Instant createdAt;

    private MerchantApiKey(UUID id, UUID merchantId, String label, String prefix, String keyHash,
                           ApiKeyStatus status, Set<ApiKeyScope> scopes, Instant lastUsedAt,
                           Instant createdAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.label = label;
        this.prefix = prefix;
        this.keyHash = keyHash;
        this.status = status;
        this.scopes = Set.copyOf(scopes);
        this.lastUsedAt = lastUsedAt;
        this.createdAt = createdAt;
    }

    /**
     * Cria uma chave ativa a partir do segredo em claro (devolvido UMA vez na
     * resposta do portal) e do seu hash SHA-256 — o par vem do port
     * {@code ApiKeyGenerator} (mesmo padrão do cadastro, Spec 12). Apenas o
     * hash e o prefixo são retidos no domínio; o texto claro nunca é persistido.
     */
    public static MerchantApiKey create(UUID merchantId, String label, String plaintext,
                                        String keyHash, Set<ApiKeyScope> scopes, Instant now) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        Objects.requireNonNull(keyHash, "keyHash must not be null");
        Objects.requireNonNull(scopes, "scopes must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (!plaintext.startsWith(KEY_PREFIX)) {
            throw new IllegalArgumentException("Chave deve começar com " + KEY_PREFIX);
        }
        String prefix = KEY_PREFIX + plaintext.substring(KEY_PREFIX.length(),
                Math.min(plaintext.length(), KEY_PREFIX.length() + PREFIX_HEX_LENGTH));
        return new MerchantApiKey(UUID.randomUUID(), merchantId, label, prefix,
                keyHash, ApiKeyStatus.ACTIVE, scopes, null, now);
    }

    public static MerchantApiKey restore(UUID id, UUID merchantId, String label, String prefix,
                                         String keyHash, ApiKeyStatus status, Set<ApiKeyScope> scopes,
                                         Instant lastUsedAt, Instant createdAt) {
        return new MerchantApiKey(id, merchantId, label, prefix, keyHash, status, scopes,
                lastUsedAt, createdAt);
    }

    /** Revoga a chave — deixa de autenticar (401 em X-Api-Key). */
    public void revoke() {
        this.status = ApiKeyStatus.REVOKED;
    }

    /** Registra a última autenticação válida com esta chave. */
    public void recordUse(Instant now) {
        this.lastUsedAt = now;
    }

    /** A chave pode autenticar? (ACTIVE e não revogada). */
    public boolean isActive() {
        return status == ApiKeyStatus.ACTIVE;
    }

    /** A chave possui o escopo informado? */
    public boolean hasScope(ApiKeyScope scope) {
        return scopes.contains(scope);
    }

    /** Escopo total = os três escopos (chave do cadastro). */
    public boolean hasTotalScope() {
        return scopes.containsAll(Set.of(ApiKeyScope.values()));
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public String label() {
        return label;
    }

    public String prefix() {
        return prefix;
    }

    public String keyHash() {
        return keyHash;
    }

    public ApiKeyStatus status() {
        return status;
    }

    public Set<ApiKeyScope> scopes() {
        return scopes;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
