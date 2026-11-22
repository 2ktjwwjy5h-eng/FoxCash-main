package com.foxcash.domain.exception;

/**
 * Já existe um bot com o mesmo token (ou a mesma plataforma) — 409
 * {@code bot_already_exists}. O {@code token_hash} é UNIQUE no banco (lookup
 * determinístico — ciphertext AES-GCM não permite busca); o serviço checa
 * antes de salvar para devolver 409 em vez de 500.
 */
public class StoreBotAlreadyExistsException extends RuntimeException {

    public StoreBotAlreadyExistsException() {
        super("Ja existe um bot de venda com este token");
    }
}
