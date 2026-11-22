package com.foxcash.domain.port.in;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.plan.Platform;

import java.time.Instant;
import java.util.UUID;

/**
 * Porta de entrada: cria uma cobranca Pix (endpoint {@code POST /api/v1/foxcash/pix/cob}).
 * O PSP retorna QR Code (base64) e Pix Copia e Cola para a cobranca criada.
 */
public interface CreatePixChargeUseCase {

    Result create(Command command);

    /**
     * Gateway somente para o vendedor (decisao de produto 2026-08-04): nenhum
     * dado pessoal do comprador e capturado — o contato com o cliente e do bot
     * do lojista. {@code buyerId} e um pseudonimo OPCIO enviado pelo bot (sem
     * PII; apenas o hash SHA-256 e persistido) para triagem antifraude (Spec 19).
     * {@code idempotencyKey} e a chave de idempotência OBRIGATÓRIA do cliente
     * (Spec 28), única por lojista — o retry com a mesma chave nunca duplica.
     *
     * <p>Spec 49 (compra de plano): {@code planId}, {@code platform} e
     * {@code platformUserId} são opcionais (null nas cobranças avulsas); quando
     * presentes, a transação carrega o destinatário para a entrega automática.
     *
     * <p>Spec 75: {@code botId} é opcional (null nas cobranças avulsas e compras
     * sem atribuição de bot) — quando presente, a transação guarda o bot de
     * venda que gerou a cobrança (funil {@code checkout_started}/{@code paid} e
     * métricas por bot).
     */
    record Command(UUID merchantId, Money amount,
                   Integer expirationSeconds, String buyerId, String description,
                   String idempotencyKey, UUID planId, Platform platform,
                   String platformUserId, Instant termsAcceptedAt, String buyerIp,
                   String buyerDevice, UUID botId, UUID profileId) {

        /**
         * Compatibilidade: cobranças avulsas (sem provas de entrega). As evidências
         * ({@code termsAcceptedAt}/{@code buyerIp}/{@code buyerDevice}) são capturadas
         * apenas na compra de plano (Spec 49+).
         */
        public Command(UUID merchantId, Money amount,
                       Integer expirationSeconds, String buyerId, String description,
                       String idempotencyKey, UUID planId, Platform platform,
                       String platformUserId) {
            this(merchantId, amount, expirationSeconds, buyerId, description,
                    idempotencyKey, planId, platform, platformUserId,
                    null, null, null, null, null);
        }

        /**
         * Compatibilidade: compra de plano (Spec 49) e fluxos que montam o comando
         * completo sem perfil (Spec 124) — {@code profileId} null (o serviço resolve
         * pelo bot ou pelo default).
         */
        public Command(UUID merchantId, Money amount,
                       Integer expirationSeconds, String buyerId, String description,
                       String idempotencyKey, UUID planId, Platform platform,
                       String platformUserId, Instant termsAcceptedAt, String buyerIp,
                       String buyerDevice, UUID botId) {
            this(merchantId, amount, expirationSeconds, buyerId, description,
                    idempotencyKey, planId, platform, platformUserId,
                    termsAcceptedAt, buyerIp, buyerDevice, botId, null);
        }

        /**
         * Cobrança do portal com perfil de operação explícito (Spec 124): o header
         * {@code X-Profile-ID} validado define o contexto (override do perfil vence
         * risco — rota viva). {@code null} nos demais fluxos (o serviço resolve
         * pelo bot ou pelo default).
         */
        public Command(UUID merchantId, Money amount,
                       Integer expirationSeconds, String buyerId, String description,
                       String idempotencyKey, UUID planId, Platform platform,
                       String platformUserId, UUID profileId) {
            this(merchantId, amount, expirationSeconds, buyerId, description,
                    idempotencyKey, planId, platform, platformUserId,
                    null, null, null, null, profileId);
        }
    }

    record Result(UUID transactionId) {}
}
