package com.foxcash.domain.port.out;

import com.foxcash.domain.model.Money;

import java.util.UUID;

/**
 * Porta de saida: integracao com o provedor para PIX de saida (payout) — Spec 36,
 * preparada para o adquirente real (Spec 38).
 *
 * <p>Espelha o {@link PixProviderClient} (Spec 10/11): implementacoes concretas
 * podem usar provedores reais (ex.: Pagar.me/Asaas de saida, adquirente a
 * escolher — ADR-005) ou um mock em desenvolvimento
 * ({@code foxcash.psp.payout-provider=mock}, default apenas em dev).
 *
 * <p><b>Contrato do adapter (Spec 38):</b>
 * <ul>
 *   <li>{@code createPayout} solicita o PIX de saida e devolve o
 *       {@link PayoutResult} com a referencia externa do PSP (usada no webhook
 *       de saida e na reconciliacao). <b>Falha sincrona</b> (timeout/4xx/5xx)
 *       deve lancar excecao — o fluxo de saque faz rollback (nada e criado).</li>
 *   <li>{@code getPayout} consulta o estado no PSP para a reconciliacao de
 *       {@code PROCESSING} orfaos e devolve o estado CANONICO:
 *       {@code PAID} (confirmado), {@code FAILED} (rejeitado) ou {@code PENDING}
 *       (ainda em processamento — a reconciliacao NAO liquida; aguarda o proximo
 *       run). O adapter mapeia o vocabulario do PSP (ex.: Pagar.me
 *       {@code processing/pending}, Asaas {@code pending}) para o canonico.</li>
 *   <li>O <b>webhook de saida</b> {@code POST /webhooks/payouts} ja entrega o
 *       desfecho no vocabulario canonico {@code PAID}/{@code FAILED} (com
 *       {@code failedReason}); {@code PENDING} nao existe no webhook (so na
 *       consulta).</li>
 *   <li>O adapter deve herdar o teste de contrato
 *       {@code PayoutProviderContractTest} (ver runbook
 *       {@code deploy/payout-homologation.md}).</li>
 * </ul>
 *
 * <p>A chave/dados bancarios do destino chegam no command ja desencriptados (o
 * adapter nunca expoe alem do que o servico de aplicacao ja conhece do snapshot).
 */
public interface PayoutProviderClient {

    /** Solicita o PIX de saida ao PSP. Falha sincrona (timeout/5xx) lanca excecao. */
    PayoutResult createPayout(PayoutCommand command);

    /** Consulta o estado de um payout no PSP (reconciliacao de PROCESSING orfaos). */
    PayoutStatus getPayout(String externalReference);

    /**
     * @param withdrawalId id do saque (referencia interna do lojista no PSP)
     * @param amount       valor do payout
     * @param destination  destino (chave Pix ou dados bancarios)
     * @param description  descricao livre (ex.: "saque lojista")
     */
    record PayoutCommand(UUID withdrawalId, Money amount, PayoutDestination destination,
                         String description) {
    }

    /** Destino do payout — pixKey (PIX_*) ou dados bancarios (BANK_ACCOUNT). */
    record PayoutDestination(String pixKey, String bankCode, String agency, String account,
                             String accountType) {
    }

    /**
     * Aceite do PSP: referencia externa usada nos webhooks/reconciliacao e o
     * provedor que ACEITOU o payout (Spec 92 — roteamento pós-fallback: com o
     * {@code PayoutProviderRouter} o valor é o vencedor real; sem router/null,
     * o caller usa o provedor configurado).
     */
    record PayoutResult(String externalReference, String provider) {

        /** Compatibilidade: aceite sem provedor (adapter direto, sem router). */
        public PayoutResult(String externalReference) {
            this(externalReference, null);
        }
    }

    /**
     * Estado CANONICO de um payout consultado no PSP (Spec 38):
     * {@code PAID} (confirmado), {@code FAILED} (rejeitado — estorno automatico)
     * ou {@code PENDING} (ainda em processamento — a reconciliacao NAO liquida;
     * o saque {@code PROCESSING} permanece para o proximo run).
     */
    enum PayoutStatus {
        PAID,
        FAILED,
        PENDING
    }

    /**
     * Erro real do PSP de payout (4xx/5xx, resposta inválida) — o fluxo de
     * saque trata como falha sincrona do {@code createPayout} (estorno do debito
     * + saque FAILED, Spec 42/B-04) ou rejeicao na consulta (Spec 36).
     */
    class PayoutProviderException extends RuntimeException {
        public PayoutProviderException(String message) {
            super(message);
        }

        public PayoutProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Timeout de rede/leitura no PSP de payout — a resposta e INCERTA (o
     * payout PODE ter sido aceito no provedor); o fluxo trata como falha
     * sincrona (estorno + FAILED; retry com o mesmo {@code withdrawal.id} e
     * idempotente no adquirente — Spec 42/B-04).
     */
    class PayoutProviderTimeoutException extends PayoutProviderException {
        public PayoutProviderTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
