package com.foxcash.domain.exception;

/**
 * Broadcast inexistente (ou fora do escopo do lojista) — 404
 * {@code broadcast_not_found}. Sempre por {@code merchantId} do principal
 * (padrão IDOR das Specs 24/31): cross-lojista → 404, nunca revela existência.
 */
public class BroadcastNotFoundException extends RuntimeException {

    public BroadcastNotFoundException() {
        super("Broadcast nao encontrado");
    }
}
