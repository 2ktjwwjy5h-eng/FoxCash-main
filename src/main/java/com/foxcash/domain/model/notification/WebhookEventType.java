package com.foxcash.domain.model.notification;

/**
 * Tipos de evento publicados no outbox e entregues via webhook de saida
 * (Spec 35). Cada valor corresponde a um ponto de origem que publica o evento
 * na mesma transacao.
 */
public enum WebhookEventType {

    /** Cobranca criada (QR Code emitido). */
    CHARGE_CREATED,

    /** Transacao aprovada (webhook PAID do PSP). */
    SALE_PAID,

    /** Transacao estornada (REFUNDED). */
    SALE_REFUNDED,

    /** Chargeback registrado. */
    CHARGEBACK_REGISTERED,

    /** Saque concluido (lojista ou administrativo do master). */
    WITHDRAWAL_COMPLETED,

    /** Fatura gerada (Spec 7 — mantido). */
    INVOICE_GENERATED,

    /** Fatura cobrada do wallet (quitada, Spec 33). */
    INVOICE_COLLECTED,

    /** Falha de job agendado (Spec 53) — evento de PLATAFORMA, sem merchant. */
    JOB_FAILED,

    /** Entrega de webhook de saida esgotou as tentativas (Spec 35/53) — alerta
     * operacional de PLATAFORMA (sem merchant; o merchantId original vai no payload). */
    OUTBOX_DEAD,

    /** Rajada de falhas de login (Spec 64) — alerta de SEGURANCA de plataforma
     * (sem merchant): N falhas de login em janela curta (brute-force/automação). */
    LOGIN_FAILURE_BURST,

    /** Rajada de 429 num grupo/rota de rate limit (Spec 64) — alerta de
     * SEGURANCA de plataforma (sem merchant): volume alto de rejeições por
     * rate limiting num grupo (indício de flood/abuso). */
    RATE_LIMIT_BURST,

    /** Rajada de webhooks rejeitados por assinatura inválida (Spec 64) — alerta
     * de SEGURANCA de plataforma (sem merchant): N rejeições por HMAC inválido
     * em janela curta (indício de replay/flood/spoofing). */
    SIGNATURE_FAILURE_BURST,

    /** Fail-open do rate limit Redis ativado (Spec 64) — alerta de SEGURANCA de
     * plataforma (sem merchant): Redis indisponível, proteção degradada
     * (ADR-010 — fail-open documentado). */
    RATE_LIMIT_FAIL_OPEN,

    /** Rajada de requests bloqueados pela inspeção de corpo (Spec 63 — camada
     * app; alerta de SEGURANCA de plataforma, sem merchant): payload suspeito
     * (SQLi/XSS/path traversal) detectado no raw body antes da auth —
     * indício de ataque automatizado na API. */
    BODY_INSPECTION_BLOCKED,

    /** Update recebido no webhook público do Telegram (Spec 74) — encaminhado ao
     * renderizador via o padrão de eventos da Spec 35 (outbox → webhook do
     * lojista). O payload carrega {@code botId} + o update raw do Telegram. */
    TELEGRAM_UPDATE,

    /** Disparo de tracking (Spec 81) — evento interno consumido pelo
     * {@code TrackingDispatchHandler} (envia aos provedores de mídia e grava
     * {@code event_logs}). NUNCA chega ao {@code MerchantNotifier} (o processor
     * intercepta antes). */
    TRACKING_DISPATCH,

    /** Falha do job de sweep do saldo do PSP (Spec 92) — alerta de PLATAFORMA
     * (sem merchant): o excedente NÃO foi transferido para a conta blindada
     * (saldo em risco de freeze). Payload mascarado (provedor/motivo, nunca chave). */
    SWEEP_FAILED,

    /** Exposição do caixa no PSP acima do limiar (Spec 92) — alerta de PLATAFORMA
     * (sem merchant): {@code available − buffer} ultrapassou
     * {@code foxcash.psp.sweep.exposure-alert} (saldo parado demais no gateway). */
    SWEEP_EXPOSURE_HIGH,

    /** Circuito aberto de adquirente de cobrança (Spec 91 — D5) — alerta de
     * PLATAFORMA (sem merchant): janela de falhas/timeouts atingida no
     * {@code PixProviderHealthRegistry} → volume desviado do provedor até o
     * cooldown/half-open. Payload mascarado (provider/reason, nunca chave). */
    PROVIDER_CIRCUIT_OPEN,

    /** Tentativa de cobrança em lojista HONEYPOT (Spec 98 — D4.5) — alerta de
     * SEGURANÇA com THROTTLE por janela (padrão Spec 64): o atacante da conta
     * trap gerou tráfego; o evento chega ao operador SEM revelar nada ao
     * atacante (a conta segue operando no provedor trap). Payload mascarado
     * (merchantId + transação, nunca chave/dados reais). */
    HONEYPOT_TRIGGERED,

    /** Device fingerprint do portal em N contas de lojista (Spec 141) — alerta
     * de SEGURANÇA com THROTTLE por hash (padrão Spec 64/HONEYPOT): o mesmo
     * dispositivo foi visto no cadastro/login de 2+ lojistas distintos
     * ({@code distinct_merchants} ≥ limiar — indício de fraude multi-conta).
     * Payload mascarado (fingerprintHash + contagem, nunca email/documento). */
    FINGERPRINT_DEVICE_SHARED;

    /**
     * Valor do header {@code X-FoxCash-Event} (Spec 148) — mapeamento 1:1 do
     * enum para o formato de mercado (lowercase com pontos): {@code SALE_PAID}
     * → {@code sale.paid}, {@code SALE_REFUNDED} → {@code sale.refunded}, etc.
     * Espelha o campo {@code event} do payload do envelope (Spec 35).
     */
    public String headerValue() {
        return name().toLowerCase().replace('_', '.');
    }
}
