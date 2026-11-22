package com.foxcash.domain.model.withdrawal;

import com.foxcash.domain.model.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Configuração de saque automático por limiar de saldo (Spec 70) — 1:1 por
 * lojista ({@code merchant_id UNIQUE}). Linha ausente = desabilitado (a API
 * devolve defaults calculados; nunca 404).
 *
 * <p>Imutável nas transições: cada {@code enable}/{@code update}/{@code
 * disable} devolve uma nova instância (padrão do {@code Withdrawal}). O
 * {@code destinationId} é OBRIGATÓRIO quando {@code enabled=true} (o job saca
 * para o destino configurado); {@code disable()} mantém o destino anterior
 * para o próximo enable ser 1-clique.
 *
 * <p>Validações do domínio: {@code threshold} via {@link Money} (rejeita mais
 * de 2 casas decimais — regra M9, Spec 26) **e** {@code threshold > 0}
 * validado explicitamente — {@code Money} permite negativo (cada uso valida
 * seu domínio, padrão de {@code PixTransaction.create}). O piso
 * {@code min-threshold} da plataforma é validado no serviço (D5.1).
 */
public class AutoWithdrawConfig {

    private final UUID id;
    private final UUID merchantId;
    private final boolean enabled;
    private final Money threshold;
    private final UUID destinationId;
    private final Instant createdAt;
    private final Instant updatedAt;

    private AutoWithdrawConfig(UUID id, UUID merchantId, boolean enabled, Money threshold,
                               UUID destinationId, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.merchantId = merchantId;
        this.enabled = enabled;
        this.threshold = threshold;
        this.destinationId = destinationId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Cria a config HABILITADA (upsert no serviço quando a linha não existe). */
    public static AutoWithdrawConfig enable(UUID merchantId, Money threshold, UUID destinationId) {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        validateThreshold(threshold);
        if (destinationId == null) {
            throw new IllegalArgumentException(
                    "destinationId é obrigatório quando o saque automático está habilitado");
        }
        Instant now = Instant.now();
        return new AutoWithdrawConfig(UUID.randomUUID(), merchantId, true, threshold,
                destinationId, now, now);
    }

    /** Transição a partir de uma config existente (novo estado). */
    public AutoWithdrawConfig update(boolean enabled, Money threshold, UUID destinationId) {
        validateThreshold(threshold);
        if (enabled && destinationId == null) {
            throw new IllegalArgumentException(
                    "destinationId é obrigatório quando o saque automático está habilitado");
        }
        Instant now = Instant.now();
        return new AutoWithdrawConfig(id, merchantId, enabled, threshold, destinationId,
                createdAt, now);
    }

    /** Desabilita mantendo o {@code destinationId} anterior (1-clique para reativar). */
    public AutoWithdrawConfig disable() {
        Instant now = Instant.now();
        return new AutoWithdrawConfig(id, merchantId, false, threshold, destinationId,
                createdAt, now);
    }

    /** Restaura do banco (persistência). */
    public static AutoWithdrawConfig restore(UUID id, UUID merchantId, boolean enabled,
                                             Money threshold, UUID destinationId,
                                             Instant createdAt, Instant updatedAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(threshold, "threshold must not be null");
        Objects.requireNonNull(destinationId, "destinationId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        return new AutoWithdrawConfig(id, merchantId, enabled, threshold, destinationId,
                createdAt, updatedAt);
    }

    private static void validateThreshold(Money threshold) {
        Objects.requireNonNull(threshold, "threshold must not be null");
        // Money.of já rejeita > 2 casas decimais (M9); aqui o > 0 é explícito —
        // Money permite negativo e cada uso valida seu domínio.
        if (threshold.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("threshold deve ser maior que zero");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID merchantId() {
        return merchantId;
    }

    public boolean enabled() {
        return enabled;
    }

    public Money threshold() {
        return threshold;
    }

    public UUID destinationId() {
        return destinationId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
