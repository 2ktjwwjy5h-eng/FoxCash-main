package com.foxcash.infrastructure.notification;

import com.foxcash.domain.model.notification.OutboxNotification;
import com.foxcash.domain.port.out.MerchantNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter MOCK de entrega de notificacoes: apenas loga o evento.
 * Em producao, trocar por um adapter real (e-mail, webhook, push).
 */
@Component
public class LoggingMerchantNotifier implements MerchantNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingMerchantNotifier.class);

    @Override
    public void deliver(OutboxNotification notification) {
        log.info("NOTIFICACAO [{}] merchant={} payload={} (adapter mock - substituir por email/webhook)",
                notification.type(), notification.merchantId(), notification.payload());
    }
}
