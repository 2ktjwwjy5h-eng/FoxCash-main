package com.foxcash.domain.model.master;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do nivel derivado do master (Spec 69, F2 — criterio 5): OPERATOR
 * ({@code can_manage_masters=false}) e ADMIN ({@code can_manage_masters=true}),
 * sem campo novo.
 */
class MasterLevelTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Test
    void operatorIsDerivedFromFalseFlag() {
        Master operator = Master.restore(java.util.UUID.randomUUID(), "operador", "hash",
                "Operador", true, false, NOW);
        assertThat(MasterLevel.of(operator)).isEqualTo(MasterLevel.OPERATOR);
    }

    @Test
    void adminIsDerivedFromTrueFlag() {
        Master admin = Master.restore(java.util.UUID.randomUUID(), "admin", "hash",
                "Admin", true, true, NOW);
        assertThat(MasterLevel.of(admin)).isEqualTo(MasterLevel.ADMIN);
    }

    @Test
    void newProfileViaCreateDefaultsToOperator() {
        Master created = Master.create("novo", "hash", "Novo"); // canManageMasters=false
        assertThat(created.canManageMasters()).isFalse();
        assertThat(MasterLevel.of(created)).isEqualTo(MasterLevel.OPERATOR);
    }
}
