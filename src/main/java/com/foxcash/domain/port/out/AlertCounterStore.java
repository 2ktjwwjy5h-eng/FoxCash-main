package com.foxcash.domain.port.out;

/**
 * Porta de saída: contador de rajada dos alertas de segurança (Spec 66 — Fase B).
 *
 * <p>Semântica de **janela fixa** por chave (`alert:{tipo}:{contexto}`):
 * incrementa e devolve `1` quando o contador atinge o limiar dentro da janela
 * (disparo único — a janela é reiniciada no disparo para evitar re-disparo
 * imediato), senão `0`. Espelha o padrão do {@link RateLimitStore} (Spec 43).
 *
 * <p>Implementações: {@code InMemoryAlertCounterStore} (default,
 * single-instância) e {@code RedisAlertCounterStore} (multi-instância — script
 * Lua atômico, limiar GLOBAL; fail-open → fallback local quando o Redis cai,
 * ADR-010). O {@code SecurityAlertPublisher} injeta esta porta e ignora o store
 * concreto.
 */
public interface AlertCounterStore {

    /**
     * Incrementa o contador da chave na janela corrente.
     *
     * @param counterKey    chave do contador (ex.: {@code "login:portal"})
     * @param threshold     limiar de disparo (>= 1)
     * @param windowSeconds tamanho da janela em segundos
     * @return {@code 1} se o limiar foi atingido nesta chamada (disparar o
     *         alerta); {@code 0} caso contrário
     */
    long incrementAndFire(String counterKey, int threshold, long windowSeconds);
}
