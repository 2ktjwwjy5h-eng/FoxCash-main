package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.MasterPasswordGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes do gerador de senha temporária de perfil master (Spec 41).
 */
class MasterPasswordGeneratorTest {

    private final MasterPasswordGenerator generator = new MasterPasswordGeneratorImpl();

    @Test
    void generatesTemporaryPasswordInMstHexFormat() {
        String password = generator.generate();
        assertNotNull(password);
        // SEC-25 (revisão de segurança 2026-08-11): 16 bytes = 128 bits de
        // entropia (mst_<32 hex>) — antes 8 bytes (64 bits), abaixo do padrão
        // para a credencial de um operador privilegiado.
        assertTrue(password.matches("^mst_[0-9a-f]{32}$"), "formato esperado mst_<32 hex>, got: " + password);
        // 128 bits: 32 caracteres hex → 4 bits por caractere.
        assertTrue(password.substring("mst_".length()).length() == 32,
                "entropia esperada de 128 bits (32 hex), got: " + password);
    }

    @Test
    void generatesDifferentPasswords() {
        assertTrue(!generator.generate().equals(generator.generate()));
    }
}
