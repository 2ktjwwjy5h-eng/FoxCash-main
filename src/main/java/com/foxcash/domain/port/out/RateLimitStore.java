package com.foxcash.domain.port.out;

/**
 * Porta de saída: store do rate limit (Spec 18/ADR-009, evoluída pela Spec 43 —
 * M-05). Semântica de **janela fixa** por chave (IP+rota).
 *
 * <p>Implementações: {@code InMemoryRateLimitStore} (default, single-instance —
 * janela em {@code ConcurrentHashMap}) e {@code RedisRateLimitStore}
 * (multi-instância — script Lua atômico {@code INCR}+{@code EXPIRE}). O
 * {@code RateLimitFilter} injeta esta porta e ignora o store concreto.
 */
public interface RateLimitStore {

    /**
     * Tenta adquirir uma permissão na janela corrente da chave.
     *
     * @param key          chave da janela (ex.: {@code "rota|ip"})
     * @param maxRequests  limite de requisições por janela
     * @param windowSeconds tamanho da janela em segundos
     * @return {@code true} se a requisição está dentro do limite (deve ser
     *         permitida); {@code false} se excedeu o limite (deve ser bloqueada
     *         com 429)
     */
    boolean tryAcquire(String key, int maxRequests, long windowSeconds);
}
