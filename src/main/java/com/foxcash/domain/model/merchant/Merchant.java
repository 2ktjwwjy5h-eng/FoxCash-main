package com.foxcash.domain.model.merchant;

import com.foxcash.domain.exception.MerchantNotActiveException;
import com.foxcash.domain.exception.HoneypotBlockedException;
import com.foxcash.domain.exception.InsufficientBalanceException;
import com.foxcash.domain.exception.OutstandingDebtException;
import com.foxcash.domain.exception.WithdrawalsBlockedException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.service.EmailValidator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lojista (merchant) da plataforma white-label.
 *
 * <p>Cadastro com nome, e-mail e senha (Spec 39): o e-mail é o identificador
 * único de login (normalizado em minúsculas); o {@code document} (CPF/CNPJ)
 * deixou de ser obrigatório — {@code null} em novos cadastros (legado preserva).
 * O {@code document} é tratado como String no dominio (texto puro); a
 * criptografia em repouso (AES-256/GCM) é aplicada na camada de infraestrutura.
 */
public class Merchant {

    private final UUID id;
    private final String name;
    private final String email;
    private final String document;
    private final String apiKeyHash;
    private MerchantStatus status;
    private final PricingPlan pricingPlan;
    private Money walletBalance;
    private boolean withdrawalsBlocked;
    private boolean outstandingDebt;
    private final Instant createdAt;

    // Spec 98 (V61): config fina por lojista (overrides; null = global).
    private MerchantConfig config;

    // Spec 128 (V75): gestão automática de risco — true = um humano (master)
    // tocou config/status do lojista → o RiskScoringEngine NÃO age (nem reduz
    // nem restaura o rate limit) — evita guerra de edição. Sticky.
    private boolean riskAutoManaged;

    private Merchant(UUID id, String name, String email, String document, String apiKeyHash,
                     MerchantStatus status, PricingPlan pricingPlan, Money walletBalance,
                     boolean withdrawalsBlocked, Instant createdAt) {
        this(id, name, email, document, apiKeyHash, status, pricingPlan, walletBalance,
                withdrawalsBlocked, createdAt, MerchantConfig.defaults());
    }

    private Merchant(UUID id, String name, String email, String document, String apiKeyHash,
                     MerchantStatus status, PricingPlan pricingPlan, Money walletBalance,
                     boolean withdrawalsBlocked, Instant createdAt, MerchantConfig config) {
        this(id, name, email, document, apiKeyHash, status, pricingPlan, walletBalance,
                withdrawalsBlocked, createdAt, config, false);
    }

    private Merchant(UUID id, String name, String email, String document, String apiKeyHash,
                     MerchantStatus status, PricingPlan pricingPlan, Money walletBalance,
                     boolean withdrawalsBlocked, Instant createdAt, MerchantConfig config,
                     boolean riskAutoManaged) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.document = document;
        this.apiKeyHash = apiKeyHash;
        this.status = status;
        this.pricingPlan = pricingPlan;
        this.walletBalance = walletBalance;
        this.withdrawalsBlocked = withdrawalsBlocked;
        this.createdAt = createdAt;
        this.config = config == null ? MerchantConfig.defaults() : config;
        this.riskAutoManaged = riskAutoManaged;
    }

    public static Merchant register(String name, String email, PricingPlan pricingPlan) {
        return register(name, email, pricingPlan, null);
    }

    /**
     * Cria um lojista com a chave de API (hash SHA-256) ja atribuida (Spec 39).
     *
     * <p>Valida nome (non-blank) e e-mail (formato válido; normalizado em
     * minúsculas). O texto claro da chave nunca é persistido nem entra no
     * dominio — apenas o hash é carregado aqui (Spec 12).
     */
    public static Merchant register(String name, String email, PricingPlan pricingPlan,
                                    String apiKeyHash) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name é obrigatório no cadastro");
        }
        if (name.length() > 120) {
            throw new IllegalArgumentException("name deve ter no máximo 120 caracteres");
        }
        if (!EmailValidator.isValid(email)) {
            throw new IllegalArgumentException("e-mail inválido");
        }
        Objects.requireNonNull(pricingPlan, "pricingPlan must not be null");
        String normalizedEmail = email.trim().toLowerCase();
        return new Merchant(UUID.randomUUID(), name.trim(), normalizedEmail, null, apiKeyHash,
                MerchantStatus.ACTIVE, pricingPlan, Money.of("0.00"), false, Instant.now());
    }

    public static Merchant restore(UUID id, String name, String email, String document, String apiKeyHash,
                                   MerchantStatus status, PricingPlan pricingPlan, Money walletBalance,
                                   boolean withdrawalsBlocked, Instant createdAt) {
        return new Merchant(id, name, email, document, apiKeyHash, status, pricingPlan,
                walletBalance, withdrawalsBlocked, createdAt);
    }

    /** Restore com a config fina (Spec 98 — V61); {@code config} null → defaults. */
    public static Merchant restore(UUID id, String name, String email, String document, String apiKeyHash,
                                   MerchantStatus status, PricingPlan pricingPlan, Money walletBalance,
                                   boolean withdrawalsBlocked, Instant createdAt, MerchantConfig config) {
        return new Merchant(id, name, email, document, apiKeyHash, status, pricingPlan,
                walletBalance, withdrawalsBlocked, createdAt, config);
    }

    /** Restore com a config fina + flag de gestão automática (Spec 128 — V75). */
    public static Merchant restore(UUID id, String name, String email, String document, String apiKeyHash,
                                   MerchantStatus status, PricingPlan pricingPlan, Money walletBalance,
                                   boolean withdrawalsBlocked, Instant createdAt, MerchantConfig config,
                                   boolean riskAutoManaged) {
        return new Merchant(id, name, email, document, apiKeyHash, status, pricingPlan,
                walletBalance, withdrawalsBlocked, createdAt, config, riskAutoManaged);
    }

    public void suspend() {
        this.status = MerchantStatus.SUSPENDED;
    }

    public void activate() {
        this.status = MerchantStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = MerchantStatus.INACTIVE;
    }

    /**
     * Ban (Spec 98): status {@code BANNED} irreversível (bloqueia API, portal,
     * saques e cobranças — todos os gates consultam o status). Não há unbanned.
     */
    public void ban() {
        this.status = MerchantStatus.BANNED;
    }

    /**
     * Atualiza a config fina (Spec 98 — D3): valida o conjunto (valores
     * negativos, {@code pix_fee_type} desconhecido, percentual 0–100,
     * threshold {@code > 0}, {@code withdrawal_fee ≥ 0}, {@code min ≤ max}
     * quando ambos, {@code rate_limit_* > 0}). Sem {@code gatewayRoute} — rota
     * é por perfil (Spec 122).
     */
    public void updateConfig(MerchantConfig newConfig) {
        Objects.requireNonNull(newConfig, "config must not be null");
        validateConfig(newConfig);
        this.config = newConfig;
    }

    /** Bloqueia a API key do lojista (Spec 98 — 403 no ApiKeyAuthFilter; portal segue). */
    public void markApiBlocked(boolean blocked) {
        this.config = new MerchantConfig(
                config.pixFeeType(), config.pixFeeFixed(), config.pixFeePercent(), config.pixFeeThreshold(),
                config.withdrawalFee(), config.pixMin(), config.pixMax(),
                config.withdrawalMin(), config.withdrawalMax(), config.rateLimitRps(),
                config.rateLimitRpm(), blocked, config.honeypot());
    }

    /** Marca/desmarca o honeypot (Spec 98 — ambiente controlado, reversível). */
    public void markHoneypot(boolean honeypot) {
        this.config = new MerchantConfig(
                config.pixFeeType(), config.pixFeeFixed(), config.pixFeePercent(), config.pixFeeThreshold(),
                config.withdrawalFee(), config.pixMin(), config.pixMax(),
                config.withdrawalMin(), config.withdrawalMax(), config.rateLimitRps(),
                config.rateLimitRpm(), config.apiBlocked(), honeypot);
    }

    private static void validateConfig(MerchantConfig c) {
        if (c.pixFeeType() != null
                && c.pixFeeType() != MerchantConfig.PixFeeType.FIXED
                && c.pixFeeType() != MerchantConfig.PixFeeType.PERCENT) {
            throw new IllegalArgumentException("pix_fee_type inválido: " + c.pixFeeType());
        }
        if (c.pixFeeFixed() != null && c.pixFeeFixed().amount().signum() < 0) {
            throw new IllegalArgumentException("pix_fee_fixed não pode ser negativo");
        }
        if (c.pixFeePercent() != null
                && (c.pixFeePercent().signum() < 0 || c.pixFeePercent().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("pix_fee_percent deve estar entre 0 e 100");
        }
        if (c.pixFeeThreshold() != null && c.pixFeeThreshold().amount().signum() <= 0) {
            throw new IllegalArgumentException("pix_fee_threshold deve ser maior que zero");
        }
        if (c.withdrawalFee() != null && c.withdrawalFee().amount().signum() < 0) {
            throw new IllegalArgumentException("withdrawal_fee não pode ser negativo");
        }
        if (c.pixMin() != null && c.pixMin().amount().signum() < 0) {
            throw new IllegalArgumentException("pix_min não pode ser negativo");
        }
        if (c.pixMax() != null && c.pixMax().amount().signum() < 0) {
            throw new IllegalArgumentException("pix_max não pode ser negativo");
        }
        if (c.withdrawalMin() != null && c.withdrawalMin().amount().signum() < 0) {
            throw new IllegalArgumentException("withdrawal_min não pode ser negativo");
        }
        if (c.withdrawalMax() != null && c.withdrawalMax().amount().signum() < 0) {
            throw new IllegalArgumentException("withdrawal_max não pode ser negativo");
        }
        if (c.pixMin() != null && c.pixMax() != null
                && c.pixMin().amount().compareTo(c.pixMax().amount()) > 0) {
            throw new IllegalArgumentException("pix_min não pode ser maior que pix_max");
        }
        if (c.withdrawalMin() != null && c.withdrawalMax() != null
                && c.withdrawalMin().amount().compareTo(c.withdrawalMax().amount()) > 0) {
            throw new IllegalArgumentException("withdrawal_min não pode ser maior que withdrawal_max");
        }
        if (c.rateLimitRps() != null && c.rateLimitRps() <= 0) {
            throw new IllegalArgumentException("rate_limit_rps deve ser maior que zero");
        }
        if (c.rateLimitRpm() != null && c.rateLimitRpm() <= 0) {
            throw new IllegalArgumentException("rate_limit_rpm deve ser maior que zero");
        }
    }

    public void ensureActive() {
        if (status != MerchantStatus.ACTIVE) {
            throw new MerchantNotActiveException(id);
        }
    }

    /** Spec 98 (D4.5): lojista honeypot → 409 {@code honeypot_blocked} (saque). */
    public void ensureNotHoneypot() {
        if (config.honeypot()) {
            throw new HoneypotBlockedException(id);
        }
    }

    /** Credita o valor de uma venda aprovada no saldo do lojista. */
    public void credit(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        this.walletBalance = walletBalance.add(amount);
    }

    /**
     * Estorno de venda (refund/chargeback): debita o saldo SEM as validações
     * de saque (ACTIVE/bloqueio/saldo). Pode deixar o saldo negativo — o
     * lojista deve à plataforma quando já sacou o valor (decisão 2026-08-04).
     */
    public void debit(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        this.walletBalance = walletBalance.subtract(amount);
    }

    /**
     * Debita o saldo para um saque (Spec 14). Valida ACTIVE + desbloqueio +
     * saldo suficiente. O bloqueio impede novos saques — nao estorna os ja
     * efetivados.
     */
    public void withdraw(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        ensureActive();
        // Spec 98 (D4.5): lojista honeypot NUNCA saca (409 honeypot_blocked).
        ensureNotHoneypot();
        if (withdrawalsBlocked) {
            throw new WithdrawalsBlockedException(id);
        }
        if (walletBalance.amount().compareTo(amount.amount()) < 0) {
            throw new InsufficientBalanceException(id, amount);
        }
        this.walletBalance = walletBalance.subtract(amount);
    }

    /**
     * Saque administrativo do master (Spec 32, bloqueio lacrado): NÃO valida
     * ACTIVE nem desbloqueio — apenas saldo suficiente. O master recolhe de
     * lojista bloqueado/suspenso; o resultado nunca é negativo. O vendedor
     * continua protegido por {@code withdraw} (nenhuma rota dele contorna).
     */
    public void adminWithdraw(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        // Spec 98 (D4.5): NÃO há saque de honeypot — nem o administrativo do
        // master (nenhuma movimentação de dinheiro sai do ambiente controlado).
        ensureNotHoneypot();
        if (walletBalance.amount().compareTo(amount.amount()) < 0) {
            throw new InsufficientBalanceException(id, amount);
        }
        this.walletBalance = walletBalance.subtract(amount);
    }

    /** Bloqueia novos saques (controle master, Spec 14). */
    public void blockWithdrawals() {
        this.withdrawalsBlocked = true;
    }

    /**
     * Carrega a flag de inadimplencia (Spec 33): true quando o lojista possui
     * receivable {@code OPEN}/{@code PARTIAL}. Flag derivada — setada pelo
     * serviço de saque sob o lock pessimista (não é persistida no merchant).
     */
    public void markOutstandingDebt(boolean hasOutstandingDebt) {
        this.outstandingDebt = hasOutstandingDebt;
    }

    /**
     * Bloqueio de saque por inadimplencia (Spec 33): lojista com dívida formal
     * aberta não saca (409 {@code outstanding_debt}). O saque administrativo do
     * master NÃO chama este método (recolhe justamente o devedor). Configuravel
     * via {@code foxcash.billing.block-withdrawals-on-debt} (default true).
     */
    public void ensureNoOutstandingDebt(boolean blockOnDebt) {
        if (blockOnDebt && outstandingDebt) {
            throw new OutstandingDebtException(id);
        }
    }

    /** Libera os saques (controle master, Spec 14). */
    public void releaseWithdrawals() {
        this.withdrawalsBlocked = false;
    }

    public UUID id() {
        return id;
    }

    /** Nome do lojista (cadastro, Spec 39). */
    public String name() {
        return name;
    }

    /** E-mail do lojista — identificador único de login (normalizado, Spec 39). */
    public String email() {
        return email;
    }

    /** Documento (CPF/CNPJ) — opcional desde a Spec 39 (null em novos cadastros). */
    public String document() {
        return document;
    }

    /** Hash SHA-256 da chave de API (null se o lojista ainda nao possui chave). */
    public String apiKeyHash() {
        return apiKeyHash;
    }

    public MerchantStatus status() {
        return status;
    }

    /** Config fina do lojista (Spec 98 — V61; defaults quando sem override). */
    public MerchantConfig config() {
        return config;
    }

    /**
     * Gestão automática de risco (Spec 128 — V75): {@code true} = um humano
     * (master) tocou config/status do lojista → o RiskScoringEngine não age
     * (nem reduz nem restaura o rate limit). Sticky (desmarcada manualmente).
     */
    public boolean riskAutoManaged() {
        return riskAutoManaged;
    }

    /** Marca que um humano (master) tocou config/status — o automático para de agir. SEM touch. */
    public void markRiskAutoManaged() {
        this.riskAutoManaged = true;
    }

    public PricingPlan pricingPlan() {
        return pricingPlan;
    }

    public Money walletBalance() {
        return walletBalance;
    }

    /** Flag de bloqueio de saques pelo master (Spec 14). */
    public boolean withdrawalsBlocked() {
        return withdrawalsBlocked;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
