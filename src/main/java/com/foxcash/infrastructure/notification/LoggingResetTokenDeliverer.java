package com.foxcash.infrastructure.notification;

import com.foxcash.domain.port.out.ResetTokenDeliverer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Adapter de entrega do token de reset via LOG (Spec 45) — apenas
 * dev/testes. O token de recuperação é **credencial de conta**: este adapter é
 * restrito a ambientes fora de produção (o {@code NotificationProperties.validate()}
 * rejeita {@code deliverer=log} no profile {@code prod} — fail-fast).
 *
 * <p>Selecionado por {@code foxcash.notification.reset-token.deliverer=log}
 * (default, dev).
 */
@Component
@ConditionalOnProperty(
        name = "foxcash.notification.reset-token.deliverer",
        havingValue = "log",
        matchIfMissing = true)
public class LoggingResetTokenDeliverer implements ResetTokenDeliverer {

    private static final Logger log = LoggerFactory.getLogger(LoggingResetTokenDeliverer.class);

    @Override
    public void deliver(String email, String resetToken, Instant expiresAt) {
        // Dev/testes apenas — nunca ativo em produção (fail-fast do properties).
        log.info("RESET TOKEN para {} (expira {}): {}", email, expiresAt, resetToken);
    }
}
