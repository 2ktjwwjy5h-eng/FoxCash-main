package com.foxcash.domain.port.out;

import com.foxcash.domain.model.master.MerchantPanelMerchant;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida: agregacoes do painel de usuarios do master (Spec 97, D4) —
 * contagens/somas em SQL (padrao M8/Spec 30, sem carregar entidades). O MED%
 * e derivado ao vivo a partir destas contagens (nunca do snapshot diario
 * {@code merchant_risk} — termometro em tempo real).
 */
public interface MerchantPanelRepository {

    /** Pix pagos = transacoes {@code APPROVED} do lojista (Spec 25 — nao conta REFUNDED/chargeback). */
    long countPaid(UUID merchantId);

    /** Pix criados = total de transacoes do lojista (qualquer status). */
    long countCreated(UUID merchantId);

    /** Pix estornados = transacoes {@code REFUNDED} do lojista (Spec 29). */
    long countRefunded(UUID merchantId);

    /** Chargebacks registrados do lojista (Spec 19). */
    long countChargebacks(UUID merchantId);

    /** Soma dos valores {@code APPROVED} do lojista (faturamento historico). */
    BigDecimal sumApprovedAmount(UUID merchantId);

    /** Provedor (gateway) da ultima cobranca do lojista (Spec 91); vazio sem cobrancas. */
    Optional<String> lastProvider(UUID merchantId);

    /**
     * Nome do comprador via lead do CRM (Spec 75/83): {@code bot_leads.name},
     * fallback {@code username}, por {@code (bot_id, platform_user_id)}. Vazio
     * sem lead → a UI mostra {@code buyerIdHash} opaco (decisao 2026-08-15).
     */
    Optional<String> buyerName(UUID botId, String platformUserId);

    /** Nome do produto/servico via {@code plans.name} (Spec 49); vazio sem plano. */
    Optional<String> planName(UUID planId);

    /**
     * Dados cadastrais + wallet + phone + document do lojista (read-model
     * agregado); vazio → 404 {@code merchant_not_found} no service.
     */
    Optional<MerchantPanelMerchant> merchantWithFees(UUID merchantId);
}
