package com.foxcash.domain.model.broadcast;

/**
 * Modo de operação do broadcast (Spec 79) — quando e como a mensagem é
 * disparada. O backend orquestra e persiste; o bot (Spec 52) executa o envio
 * com rate limit nativo (sem fila externa — decisão do usuário 2026-08-12).
 */
public enum BroadcastMode {
    /** Enviar agora — o bot busca na próxima poll ({@code scheduled_at} NULL). */
    NOW,
    /** Agendada — reusa o mecanismo de "vencidas" da Spec 51. */
    SCHEDULED,
    /** Bem-vindos — disparo automático no evento de entrada de membro. */
    WELCOME,
    /** Repost — reenvio a cada {@code interval_seconds} (mín. 1h) via job. */
    REPOST,
}
