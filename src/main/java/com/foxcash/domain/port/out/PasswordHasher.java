package com.foxcash.domain.port.out;

/**
 * Porta de saída: hash de senha (Spec 13). Implementado com BCrypt na
 * infraestrutura — o domínio nunca vê a senha em claro.
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hashedPassword);
}
