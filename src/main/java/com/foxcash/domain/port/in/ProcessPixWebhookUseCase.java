package com.foxcash.domain.port.in;

import java.math.BigDecimal;

/**
 * Porta de entrada: processa eventos de webhook do provedor Pix (PSP).
 *
 * <p>O status chega como String crua do PSP (ex.: "PAID"); o servico de
 * aplicacao normaliza para {@code TransactionStatus} do dominio.
 */
public interface ProcessPixWebhookUseCase {

    void process(WebhookEvent event);

    /**
     * @param externalReference referencia externa da cobranca (endToEndId no mock)
     * @param status            vocabulario do PSP (PAID/REFUNDED/...)
     * @param amount            valor informado pelo PSP (null quando o PSP nao envia;
     *                          usado na validacao do PAID — Spec 42/B-05)
     */
    record WebhookEvent(String externalReference, String status, BigDecimal amount) {
        public WebhookEvent(String externalReference, String status) {
            this(externalReference, status, null);
        }
    }
}
