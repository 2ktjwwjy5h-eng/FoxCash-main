package com.foxcash.infrastructure.security;

import com.foxcash.application.service.SecurityAlertPublisher;
import com.foxcash.domain.port.out.RateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Store do rate limit **distribuído (Redis)** (Spec 43 — M-05; ADR-010).
 *
 * <p>Janela fixa por chave (IP+rota) com script Lua **atômico** — {@code INCR}
 * + {@code EXPIRE} (TTL = {@code window-seconds}, setado apenas na 1ª requisição
 * da janela) — consistente e sem corrida entre instâncias: N requests
 * distribuídos entre N pods compartilham o MESMO contador.
 *
 * <p><b>Fail-open:</b> se o Redis estiver indisponível, {@code tryAcquire}
 * permite a requisição (retorna {@code true}) com {@code log.warn} throttled
 * (uma vez por janela) — o gateway de pagamento não pode parar de aceitar
 * tráfego por falha de infra auxiliar (decisão ADR-010; a limitação é proteção
 * secundária).
 *
 * <p>Ativado por {@code foxcash.security.rate-limit.store=redis} (default
 * {@code memory} — single-instance sem Redis).
 */
@Component
@ConditionalOnProperty(name = "foxcash.security.rate-limit.store",
        havingValue = "redis", matchIfMissing = false)
public class RedisRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);

    private static final String KEY_PREFIX = "rl:";

    /** Script atômico: INCR + EXPIRE (TTL só na 1ª req da janela) e devolve o contador. */
    private static final DefaultRedisScript<Long> INCR_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1])\n"
                    + "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n"
                    + "return current",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final SecurityAlertPublisher securityAlerts;

    /** Instante do último WARN de fail-open (throttle: 1 por janela). */
    private volatile long lastWarnAtMillis = 0L;

    public RedisRateLimitStore(StringRedisTemplate redisTemplate, SecurityAlertPublisher securityAlerts) {
        this.redisTemplate = redisTemplate;
        this.securityAlerts = securityAlerts;
    }

    @Override
    public boolean tryAcquire(String key, int maxRequests, long windowSeconds) {
        try {
            Long current = redisTemplate.execute(INCR_EXPIRE_SCRIPT,
                    List.of(KEY_PREFIX + key), String.valueOf(windowSeconds));
            return current == null || current <= maxRequests;
        } catch (RuntimeException e) {
            // Fail-open (ADR-010): Redis indisponível → permite a requisição. O log é
            // throttled (uma vez por janela) para não inundar o log de erro.
            warnThrottled("Rate limit Redis indisponivel — fail-open (permitindo requisicao): "
                    + e.getMessage(), windowSeconds);
            // Spec 64: fail-open ativado → alerta de segurança (throttled no publisher).
            securityAlerts.recordRateLimitFailOpen("redis-indisponivel");
            return true;
        }
    }

    private void warnThrottled(String message, long windowSeconds) {
        long now = System.currentTimeMillis();
        long throttleMs = Math.max(windowSeconds * 1000L, 1_000L);
        if (now - lastWarnAtMillis > throttleMs) {
            lastWarnAtMillis = now;
            log.warn(message);
        }
    }
}
