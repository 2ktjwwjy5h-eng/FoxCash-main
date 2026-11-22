package com.foxcash.domain.port.in;

import java.util.UUID;

/**
 * Porta de entrada: cadastro de lojistas (merchants) — Spec 39.
 *
 * <p>Cadastro com **nome, e-mail e senha** (sem CPF/CNPJ): o e-mail é o
 * identificador único de login (normalizado em minúsculas); a senha é escolhida
 * pelo usuário (apenas o hash BCrypt é persistido). O plano de precificacao é
 * definido globalmente pela plataforma (application.yml: foxcash.pricing).
 */
public interface RegisterMerchantUseCase {

    Result register(Command command);

    /**
     * @param name            nome do lojista (1–120)
     * @param email           e-mail válido, único (identificador de login)
     * @param password        senha escolhida pelo usuário (8–72 caracteres)
     * @param fingerprintHash hash SHA-256 (64 hex) do navegador (Spec 141) —
     *                        sinal de risco PASSIVO; {@code null} quando ausente
     */
    record Command(String name, String email, String password, String fingerprintHash) {

        /** Compat: cadastro sem fingerprint (Spec 141 — campo opcional). */
        public Command(String name, String email, String password) {
            this(name, email, password, null);
        }
    }

    /**
     * @param merchantId id do lojista criado
     * @param apiKey     chave de API em texto claro (formato {@code mch_<32 hex>}),
     *                   devolvida **uma unica vez** no cadastro — nunca persistida
     */
    record Result(UUID merchantId, String apiKey) {}
}
