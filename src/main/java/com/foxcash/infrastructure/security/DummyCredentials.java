package com.foxcash.infrastructure.security;

/**
 * Hash BCrypt de senha aleatória usado apenas para normalizar o tempo de
 * resposta de logins de credencial inexistente (anti-enumeração por timing —
 * auditoria 2026-08-05, B-01).
 *
 * <p>Quando o usuário/e-mail não existe, o serviço de autenticação executa um
 * {@code matches} contra este hash antes de lançar {@code 401}: o custo do
 * BCrypt é o mesmo de uma verificação real, então um atacante não consegue
 * medir o tempo e descobrir quais contas existem. Nunca corresponde a uma
 * conta real.
 */
public final class DummyCredentials {

    public static final String DUMMY_BCRYPT_HASH =
            "$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

    private DummyCredentials() {
    }
}
