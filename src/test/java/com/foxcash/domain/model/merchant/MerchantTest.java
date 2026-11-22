package com.foxcash.domain.model.merchant;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.pricing.PricingPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Testes do saldo (wallet_balance) do lojista.
 */
class MerchantTest {

    @Test
    void registerStartsWithZeroBalance() {
        Merchant merchant = Merchant.register("Loja A", "loja@example.com", PricingPlan.fixed());
        assertEquals(Money.of("0.00"), merchant.walletBalance());
    }

    @Test
    void creditIncreasesBalance() {
        Merchant merchant = Merchant.register("Loja A", "loja@example.com", PricingPlan.fixed());
        merchant.credit(Money.of("150.00"));
        merchant.credit(Money.of("49.99"));
        assertEquals(Money.of("199.99"), merchant.walletBalance());
    }

    @Test
    void debitDecreasesBalanceAndAllowsNegative() {
        Merchant merchant = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("50.00"), false, Instant.now());
        merchant.debit(Money.of("80.00"));
        // Estorno maior que o saldo → saldo devedor (lojista deve à plataforma).
        assertEquals(Money.of("-30.00"), merchant.walletBalance());
    }

    @Test
    void restoreKeepsBalance() {
        UUID id = UUID.randomUUID();
        Merchant merchant = Merchant.restore(id, "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("1234.56"), false, Instant.now());
        assertEquals(Money.of("1234.56"), merchant.walletBalance());
        assertEquals(id, merchant.id());
    }

    @Test
    void restoreKeepsApiKeyHash() {
        UUID id = UUID.randomUUID();
        String hash = "a".repeat(64);
        Merchant merchant = Merchant.restore(id, "Loja A LTDA", null, "12345678000199", hash,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("0.00"), false, Instant.now());
        assertEquals(hash, merchant.apiKeyHash());
    }

    @Test
    void withdrawDebitsBalanceWhenActiveAndUnblocked() {
        Merchant merchant = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("100.00"), false, Instant.now());
        merchant.withdraw(Money.of("40.00"));
        assertEquals(Money.of("60.00"), merchant.walletBalance());
    }

    @Test
    void blockedMerchantCannotWithdraw() {
        Merchant merchant = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("100.00"), true, Instant.now());
        assertThrows(com.foxcash.domain.exception.WithdrawalsBlockedException.class,
                () -> merchant.withdraw(Money.of("10.00")));
        assertEquals(Money.of("100.00"), merchant.walletBalance());
    }

    @Test
    void inactiveMerchantCannotWithdraw() {
        // Mutation (VoidMethodCall L129): chamada a ensureActive removida — o
        // saque de lojista INATIVO/SUSPENDED deve lançar MerchantNotActiveException.
        Merchant merchant = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.INACTIVE, PricingPlan.fixed(), Money.of("100.00"), false, Instant.now());
        assertThrows(com.foxcash.domain.exception.MerchantNotActiveException.class,
                () -> merchant.withdraw(Money.of("10.00")));
        assertEquals(Money.of("100.00"), merchant.walletBalance());
    }

    @Test
    void blockAndReleaseToggleFlag() {
        Merchant merchant = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("0.00"), false, Instant.now());
        merchant.blockWithdrawals();
        assertEquals(true, merchant.withdrawalsBlocked());
        merchant.releaseWithdrawals();
        assertEquals(false, merchant.withdrawalsBlocked());
    }

    @Test
    void registerWithoutApiKeyHashHasNullHash() {
        Merchant merchant = Merchant.register("Loja A", "loja@example.com", PricingPlan.fixed());
        assertEquals(null, merchant.apiKeyHash());
    }

    @Test
    void registerKeepsName() {
        // Mutation (EmptyObjectReturnVals): name() trocado por "" — verifica o valor.
        Merchant merchant = Merchant.register("Loja A", "loja@example.com", PricingPlan.fixed());
        assertEquals("Loja A", merchant.name());
    }

    @Test
    void registerAcceptsNameAtExactMaxLength() {
        // Boundary do nome (mutation: length() > 120 <-> >= 120): 120 e aceito.
        String name = "a".repeat(120);
        Merchant merchant = Merchant.register(name, "loja@example.com", PricingPlan.fixed());
        assertEquals(name, merchant.name());
    }

    @Test
    void registerRejectsNameOverMaxLength() {
        assertThrows(IllegalArgumentException.class,
                () -> Merchant.register("a".repeat(121), "loja@example.com", PricingPlan.fixed()));
    }

    @Test
    void withdrawWithExactBalanceSucceeds() {
        // Boundary do saldo (mutation: compareTo < 0 <-> <= 0): saque IGUAL ao
        // saldo e permitido (saldo zera, sem InsufficientBalanceException).
        Merchant merchant = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("100.00"), false, Instant.now());
        merchant.withdraw(Money.of("100.00"));
        assertEquals(Money.of("0.00"), merchant.walletBalance());
    }

    @Test
    void adminWithdrawWithExactBalanceSucceeds() {
        // Boundary do saldo (mutation: compareTo < 0 <-> <= 0): saque
        // administrativo IGUAL ao saldo e permitido (saldo zera).
        Merchant merchant = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("100.00"), false, Instant.now());
        merchant.adminWithdraw(Money.of("100.00"));
        assertEquals(Money.of("0.00"), merchant.walletBalance());
    }

    @Test
    void adminWithdrawWithInsufficientBalanceThrows() {
        Merchant merchant = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("50.00"), false, Instant.now());
        assertThrows(com.foxcash.domain.exception.InsufficientBalanceException.class,
                () -> merchant.adminWithdraw(Money.of("100.00")));
        assertEquals(Money.of("50.00"), merchant.walletBalance());
    }

    // ---- Spec 98 (V61): config fina por lojista + BANNED + honeypot ----

    private static final MerchantConfig CONFIG = new MerchantConfig(
            MerchantConfig.PixFeeType.FIXED, Money.of("1.00"), null, null, null, null, null, null, null,
            null, null, false, false);

    private Merchant activeMerchant() {
        return Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("0.00"), false, Instant.now());
    }

    @Test
    void restoreWithoutConfigDefaultsToEmpty() {
        Merchant merchant = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, null, null,
                MerchantStatus.ACTIVE, PricingPlan.fixed(), Money.of("0.00"), false, Instant.now());
        assertEquals(false, merchant.config().honeypot());
        assertEquals(false, merchant.config().apiBlocked());
    }

    @Test
    void updateConfigReplacesConfig() {
        Merchant merchant = activeMerchant();
        merchant.updateConfig(CONFIG);
        assertEquals(MerchantConfig.PixFeeType.FIXED, merchant.config().pixFeeType());
        assertEquals(Money.of("1.00"), merchant.config().pixFeeFixed());
    }

    @Test
    void updateConfigRejectsNegativeFee() {
        Merchant merchant = activeMerchant();
        MerchantConfig bad = new MerchantConfig(MerchantConfig.PixFeeType.FIXED,
                Money.of("-1.00"), null, null, null, null, null, null, null, null, null, false, false);
        assertThrows(IllegalArgumentException.class, () -> merchant.updateConfig(bad));
    }

    @Test
    void updateConfigRejectsPercentOutOfRange() {
        Merchant merchant = activeMerchant();
        MerchantConfig bad = new MerchantConfig(MerchantConfig.PixFeeType.PERCENT,
                null, new java.math.BigDecimal("101"), null, null, null, null, null, null, null, null, false, false);
        assertThrows(IllegalArgumentException.class, () -> merchant.updateConfig(bad));
    }

    @Test
    void updateConfigRejectsMinGreaterThanMax() {
        Merchant merchant = activeMerchant();
        MerchantConfig bad = new MerchantConfig(null, null, null, null, null,
                Money.of("200.00"), Money.of("100.00"), null, null, null, null, false, false);
        assertThrows(IllegalArgumentException.class, () -> merchant.updateConfig(bad));
    }

    @Test
    void banSetsStatusBanned() {
        Merchant merchant = activeMerchant();
        merchant.ban();
        assertEquals(MerchantStatus.BANNED, merchant.status());
    }

    @Test
    void bannedMerchantCannotWithdraw() {
        Merchant merchant = Merchant.restore(UUID.randomUUID(), "Loja A LTDA", null, "12345678000199", null,
                MerchantStatus.BANNED, PricingPlan.fixed(), Money.of("100.00"), false, Instant.now());
        assertThrows(com.foxcash.domain.exception.MerchantNotActiveException.class,
                () -> merchant.withdraw(Money.of("10.00")));
    }

    @Test
    void markApiBlockedTogglesFlag() {
        Merchant merchant = activeMerchant();
        merchant.markApiBlocked(true);
        assertEquals(true, merchant.config().apiBlocked());
        merchant.markApiBlocked(false);
        assertEquals(false, merchant.config().apiBlocked());
    }

    @Test
    void markHoneypotTogglesFlag() {
        Merchant merchant = activeMerchant();
        merchant.markHoneypot(true);
        assertEquals(true, merchant.config().honeypot());
        merchant.markHoneypot(false);
        assertEquals(false, merchant.config().honeypot());
    }

    @Test
    void honeypotMerchantCannotWithdraw() {
        Merchant merchant = activeMerchant();
        merchant.markHoneypot(true);
        assertThrows(com.foxcash.domain.exception.HoneypotBlockedException.class,
                () -> merchant.withdraw(Money.of("10.00")));
    }

    @Test
    void honeypotMerchantCannotAdminWithdraw() {
        // NÃO há saque de honeypot — nem o administrativo do master (D4.5).
        Merchant merchant = activeMerchant();
        merchant.markHoneypot(true);
        assertThrows(com.foxcash.domain.exception.HoneypotBlockedException.class,
                () -> merchant.adminWithdraw(Money.of("10.00")));
    }

    @Test
    void unmarkingHoneypotRestoresWithdrawals() {
        Merchant merchant = activeMerchant();
        merchant.markHoneypot(true);
        merchant.markHoneypot(false);
        merchant.withdraw(Money.of("0.00")); // sem exceção (saldo 0 — saque 0 OK)
    }
}
