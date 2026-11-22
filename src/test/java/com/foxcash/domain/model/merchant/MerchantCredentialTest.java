package com.foxcash.domain.model.merchant;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes da credencial do portal do lojista (Spec 20/40) — incluindo a troca de
 * senha no reset (Spec 40).
 */
class MerchantCredentialTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    @Test
    void changePasswordUpdatesHashAndUpdatedAt() {
        MerchantCredential credential = MerchantCredential.restore(
                UUID.randomUUID(), UUID.randomUUID(), "$2a$10$bcrypt-antigo", NOW, NOW, null);

        Instant later = NOW.plusSeconds(60);
        credential.changePassword("$2a$10$bcrypt-novo", later);

        assertEquals("$2a$10$bcrypt-novo", credential.passwordHash());
        assertEquals(later, credential.updatedAt());
        // createdAt e lastUsedAt permanecem intactos.
        assertEquals(NOW, credential.createdAt());
        assertEquals(null, credential.lastUsedAt());
    }

    @Test
    void changePasswordRejectsNullHash() {
        MerchantCredential credential = MerchantCredential.restore(
                UUID.randomUUID(), UUID.randomUUID(), "$2a$10$bcrypt-antigo", NOW, NOW, null);

        assertThrows(NullPointerException.class, () -> credential.changePassword(null, NOW));
    }

    // ---------- Auditoria 2026-08-05 (B-06): lockout por conta ----------

    @Test
    void failedLoginsIncrementCounterUntilLockout() {
        MerchantCredential credential = MerchantCredential.restore(
                UUID.randomUUID(), UUID.randomUUID(), "$2a$10$bcrypt", NOW, NOW, null);

        for (int i = 0; i < 4; i++) {
            credential.registerFailedLogin(NOW, 5, java.time.Duration.ofMinutes(15));
        }
        assertFalse(credential.isLocked(NOW));
        assertEquals(4, credential.failedLoginAttempts());

        // 5ª falha → lockout disparado e contador zerado (tentativas novas pós-janela).
        credential.registerFailedLogin(NOW, 5, java.time.Duration.ofMinutes(15));
        assertTrue(credential.isLocked(NOW));
        assertEquals(0, credential.failedLoginAttempts());
        assertEquals(NOW.plus(java.time.Duration.ofMinutes(15)), credential.lockedUntil());
    }

    @Test
    void lockoutExpiresAndSuccessfulLoginClearsState() {
        MerchantCredential credential = MerchantCredential.restore(
                UUID.randomUUID(), UUID.randomUUID(), "$2a$10$bcrypt", NOW, NOW, null,
                0, NOW.minusSeconds(1));

        // Lockout expirado (no passado) → não bloqueia mais.
        assertFalse(credential.isLocked(NOW));

        // Login bem-sucedido limpa o estado.
        credential.registerSuccessfulLogin();
        assertNull(credential.lockedUntil());
        assertEquals(0, credential.failedLoginAttempts());
    }

    @Test
    void exposesIdAndLastUsedAt() {
        // Mutations (NullReturnVals L109/L130): id e lastUsedAt expostos pelos
        // getters (mutante trocaria por null).
        UUID id = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Instant lastUsed = NOW.minusSeconds(120);
        MerchantCredential credential = MerchantCredential.restore(
                id, merchantId, "$2a$10$bcrypt", NOW, NOW, lastUsed);

        assertEquals(id, credential.id());
        assertEquals(lastUsed, credential.lastUsedAt());
        // Mutation (NullReturnVals L113): merchantId exposto pelo getter.
        assertEquals(merchantId, credential.merchantId());
    }

    @Test
    void createIssuesFreshCredential() {
        // Mutation (NullReturnVals L49): create devolve a credencial criada (o
        // mutante trocaria por null — NPE em id()); merchantId exposto.
        UUID merchantId = UUID.randomUUID();
        MerchantCredential created = MerchantCredential.create(merchantId, "$2a$10$bcrypt", NOW);

        assertNotNull(created.id());
        assertEquals(merchantId, created.merchantId());
    }
}
