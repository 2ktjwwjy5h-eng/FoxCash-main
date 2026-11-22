package com.foxcash.infrastructure.security;

import com.foxcash.domain.port.out.MasterPasswordGenerator;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Implementacao do port {@link MasterPasswordGenerator} (Spec 41).
 *
 * <p>Gera senha temporaria no formato {@code mst_<32 hex>} (SecureRandom). A
 * senha em claro e devolvida UMA vez no create do perfil; apenas o hash BCrypt
 * e persistido em {@code masters.password_hash}.
 *
 * <p><b>SEC-25 (revisão de segurança 2026-08-11):</b> 16 bytes (128 bits) — a
 * senha do master é a credencial de um operador privilegiado (Spec 41) e não
 * tinha fluxo de rotação; os 64 bits anteriores (8 bytes) ficavam abaixo do
 * padrão para credencial desse valor. Hashes existentes continuam válidos (o
 * formato só muda para NOVAS senhas geradas).
 */
@Component
public class MasterPasswordGeneratorImpl implements MasterPasswordGenerator {

    private static final String PREFIX = "mst_";
    private static final int RANDOM_HEX_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generate() {
        byte[] random = new byte[RANDOM_HEX_LENGTH / 2];
        secureRandom.nextBytes(random);
        return PREFIX + HexFormat.of().formatHex(random);
    }
}
