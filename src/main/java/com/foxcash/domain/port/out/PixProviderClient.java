package com.foxcash.domain.port.out;

import com.foxcash.domain.model.Money;

import java.util.UUID;

/**
 * Porta de saida: integracao com o provedor de pagamentos Pix (PSP).
 * Implementacoes concretas podem usar provedores reais (ex.: Gerencianet,
 * Celcoin, Pagar.me) ou um mock em desenvolvimento.
 */
public interface PixProviderClient {

    CreateChargeResult createCharge(CreateChargeCommand command);

    /**
     * Consulta o estado da cobrança no PSP pelo {@code code} (= transactionId) —
     * Spec 91, poll anti-dupla-cobrança. No timeout da criação o
     * {@code external_reference} é DESCONHECIDO (a resposta do PSP se perdeu); o
     * único identificador que conhecemos é o {@code code} que enviamos. Cada
     * adquirente guarda o code na cobrança (Pagar.me: query {@code ?code=}; Asaas:
     * o code vai no {@code externalReference}; Mock: cache por transactionId).
     *
     * <p>Timeout/5xx da consulta → {@link PixProviderTimeoutException} (resposta
     * incerta — o router trata como "não confirmou que não criou").
     *
     * @param code transactionId da cobrança (UUID como string)
     */
    GetChargeResult getChargeByCode(String code);

    /**
     * @param transactionId idempotency code enviado ao PSP (Spec 28 — retry com a
     *                      MESMA chave re-envia o mesmo code)
     * @param merchantId    lojista dono da cobrança (Spec 91 — o router resolve a
     *                      política de prioridade por risco do lojista; adapters
     *                      diretos ignoram)
     * @param expirationSeconds validade da cobrança em segundos (null = usa o
     *                           default configurado do PSP)
     * @param profileId     perfil de operação da cobrança (Spec 124 — rota viva):
     *                      o router usa {@code resolveProviders(merchantId, profileId)}
     *                      → override do perfil vence risco (Spec 122). null =
     *                      política por risco atual (regressão).
     */
    record CreateChargeCommand(UUID transactionId, UUID merchantId, Money amount, String pixKey,
                               String description, Integer expirationSeconds, UUID profileId) {

        /** Compatibilidade: cobranças sem perfil (política por risco — regressão). */
        public CreateChargeCommand(UUID transactionId, UUID merchantId, Money amount, String pixKey,
                                   String description, Integer expirationSeconds) {
            this(transactionId, merchantId, amount, pixKey, description, expirationSeconds, null);
        }
    }

    /**
     * @param externalReference referencia da cobranca no PSP (endToEndId no mock)
     * @param pixQrCode         QR Code em base64 (imagem)
     * @param pixCopyPaste      BR Code ("Pix Copia e Cola")
     * @param provider          adquirente que CRIOU a cobrança (Spec 91 — o router
     *                          o preenche; null com adapter direto)
     */
    record CreateChargeResult(String externalReference, String pixQrCode, String pixCopyPaste,
                              String provider) {

        /** Compatibilidade: resultado sem provedor (adapter direto, sem router). */
        public CreateChargeResult(String externalReference, String pixQrCode, String pixCopyPaste) {
            this(externalReference, pixQrCode, pixCopyPaste, null);
        }
    }

    /**
     * Estado canônico da consulta pelo code (Spec 91): {@code found} = cobrança
     * existe (com QR); {@code status} é o estado bruto do PSP (ex.: "pending",
     * "PAID") quando disponível — o router só usa {@code found} no poll.
     */
    record GetChargeResult(boolean found, String pixQrCode, String pixCopyPaste, String status) {}

    /**
     * Erro real do PSP (4xx/5xx, resposta inválida) ao criar a cobrança — a
     * chave de idempotência já produziu resultado e o retry responde 409
     * {@code idempotency_conflict} (Spec 28).
     */
    class PixProviderException extends RuntimeException {
        public PixProviderException(String message) {
            super(message);
        }

        public PixProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Timeout de rede/leitura no PSP (Spec 28) — a resposta é INCERTA: a
     * cobrança PODE ter sido criada no provedor. O fluxo mantém a transação
     * {@code PENDING} (sem QR) e responde 502 {@code psp_timeout}; o retry com
     * a mesma chave re-envia o {@code code} (= transactionId) ao PSP real.
     */
    class PixProviderTimeoutException extends PixProviderException {
        public PixProviderTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
