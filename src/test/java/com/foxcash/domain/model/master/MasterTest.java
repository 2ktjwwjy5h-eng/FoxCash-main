package com.foxcash.domain.model.master;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do agregado {@link Master} e da {@link MasterSession} (Spec 13):
 * getters do núcleo, fábricas create/restore e estado de lockout exposto
 * (auditoria B-08).
 */
class MasterTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final UUID MASTER_ID = UUID.randomUUID();

    @Test
    void exposesCreatedAtAndLockedUntil() {
        // Mutations (NullReturnVals L137/L169): createdAt/lockedUntil devolvem os
        // valores capturados no restore (mutante trocaria por null).
        Instant createdAt = NOW.minusSeconds(3600);
        Instant lockedUntil = NOW.plusSeconds(900);
        Master master = Master.restore(MASTER_ID, "admin", "hash", "Admin", true, true,
                createdAt, 5, lockedUntil);

        assertEquals(createdAt, master.createdAt());
        assertEquals(lockedUntil, master.lockedUntil());
        // Mutation (EmptyObjectReturnVals L125): username exposto pelo getter
        // (mutante devolveria "").
        assertEquals("admin", master.username());
        // Mutation (BooleanFalseReturnVals L117): canManageMasters reflete o
        // flag restaurado (mutante devolveria false mesmo para admin).
        assertTrue(master.canManageMasters());
    }

    @Test
    void createDefaultsToActiveNonAdmin() {
        // Mutations (NullReturnVals L66/L80): create devolve o perfil criado (o
        // mutante trocaria por null); BooleanTrue/FalseReturnVals L117:
        // canManageMasters nasce false (não-admin).
        Master created = Master.create("ops", "hash", "Ops");

        assertNotNull(created.id());
        assertTrue(created.isActive());
        assertFalse(created.canManageMasters());
        assertEquals("ops", created.username());
    }

    @Test
    void restoreMasterSessionExposesFields() {
        // Mutation (NullReturnVals L43): restore devolve a sessão restaurada (o
        // mutante trocaria por null); getters id/createdAt/expiresAt/revokedAt
        // expõem os valores capturados.
        Instant createdAt = NOW.minusSeconds(600);
        Instant expiresAt = NOW.plusSeconds(600);
        UUID masterId = UUID.randomUUID();
        MasterSession session = MasterSession.restore(MASTER_ID, masterId,
                "tokhash", createdAt, expiresAt, NOW);

        assertNotNull(session.id());
        assertEquals(MASTER_ID, session.id());
        assertEquals(masterId, session.masterId());
        assertEquals(createdAt, session.createdAt());
        assertEquals(expiresAt, session.expiresAt());
        assertEquals(NOW, session.revokedAt());
    }

    // ---------- SEC-25 (revisão de segurança 2026-08-11): troca de senha ----------

    @Test
    void changePasswordUpdatesHash() {
        // SEC-25: o domínio aceita o NOVO hash BCrypt (a senha em claro nunca
        // entra no agregado — o hash é calculado na infraestrutura).
        Master master = Master.create("ops", "hash-antigo", "Ops");
        master.changePassword("$2a$10$hash-novo");
        assertEquals("$2a$10$hash-novo", master.passwordHash());
    }

    @Test
    void changePasswordRejectsNullHash() {
        Master master = Master.create("ops", "hash-antigo", "Ops");
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> master.changePassword(null));
    }
}
