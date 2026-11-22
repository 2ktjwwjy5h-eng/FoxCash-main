package com.foxcash.domain.port.out;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.crm.CrmLeadStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saída: persistência de leads de bot (Spec 75). O registro é
 * idempotente por {@code UNIQUE (bot_id, platform_user_id)} — o adapter
 * converte violação de unicidade em {@code Optional.empty()} (no-op).
 */
public interface BotLeadRepository {

    /** Lookup de um lead (para a idempotência do registro). */
    Optional<BotLead> findByBotIdAndPlatformUserId(UUID botId, String platformUserId);

    /**
     * Grava um lead. Retorna o lead persistido, ou {@code Optional.empty()}
     * quando o lead já existe (violação da UNIQUE — replay).
     */
    Optional<BotLead> saveIfAbsent(BotLead lead);

    /**
     * Atualiza um lead EXISTENTE ({@code status} derivado do funil — Spec 83 —
     * e {@code handoffStatus} — Spec 90). Preserva o {@code id} da linha; sem
     * efeito se o lead não existir (o registro acontece via {@link #saveIfAbsent}).
     */
    void save(BotLead lead);

    /**
     * ID persistido do lead ({@code bot_leads.id} — referenciado por
     * {@code chat_messages.lead_id}, Spec 83). Empty se o lead não existe.
     */
    Optional<UUID> findIdByBotIdAndPlatformUserId(UUID botId, String platformUserId);

    /**
     * Lead por id com escopo do lojista (Spec 83 C3 — 404 cross-lojista sem
     * revelar existência). Empty se o lead não existe ou é de outro lojista.
     */
    Optional<BotLead> findByIdAndMerchantId(UUID merchantId, UUID id);

    /**
     * Lista paginada de leads do lojista (Spec 83 C3 — busca q + filtros).
     * {@code botIds} (Spec 124) opcional: null → todos os bots do lojista
     * (regressão); presente → só leads de bots do perfil (vazio → sem leads).
     */
    PageResult<BotLead> search(UUID merchantId, List<UUID> botIds, String q, UUID botId,
                               CrmLeadStatus status,
                               Instant from, Instant to, int page, int size);

    /** Contagem por status do CRM no período (cards do painel — Spec 83 C4); botIds opcional (Spec 124). */
    Map<CrmLeadStatus, Long> countByStatusForMerchant(UUID merchantId, List<UUID> botIds,
                                                      Instant from, Instant to);

    /** Total de leads de um bot no período (métricas, Spec 75). */
    long countByBotIdAndFirstSeenAtBetween(UUID botId, Instant from, Instant to);

    /** Total de leads de todos os bots no período (master). */
    long countAllByFirstSeenAtBetween(Instant from, Instant to);

    /** Total de leads dos bots de um lojista no período (portal agregado). */
    long countByMerchantIdAndFirstSeenAtBetween(UUID merchantId, Instant from, Instant to);

    /** Leads por bot no período — chave {@code bot_id} → contagem. */
    Map<UUID, Long> countGroupedByBotId(List<UUID> botIds, Instant from, Instant to);

    /** Lead recente do carrossel do shell (Spec 101) — id, nome e última interação. */
    record RecentLead(UUID id, String name, Instant lastInteractionAt) {
    }

    /**
     * Leads recentes do lojista para o shell (Spec 101) — ordenados pela última
     * interação ({@code updated_at}; fallback {@code created_at}) desc, cap
     * {@code limit}. {@code botIds} (Spec 124) opcional: null → todos os bots do
     * lojista (regressão); presente → só leads de bots do perfil (vazio → sem
     * leads — header inválido nunca vaza).
     */
    List<RecentLead> findRecentByMerchantId(UUID merchantId, List<UUID> botIds, int limit);

    /**
     * Enriquecimento do CRM (Spec 119): última interação (coluna "Última
     * interação" — {@code bot_leads.updated_at} com fallback {@code created_at})
     * e nome do plano da última compra APPROVED (coluna "Plano" — join
     * {@code pix_transactions.plan_id → plans.name}). Empty se o lead não existe
     * ou é de outro lojista (mesmo escopo de {@link #findByIdAndMerchantId}).
     */
    Optional<LeadEnrichment> findEnrichment(UUID merchantId, UUID leadId);

    /** Dados aditivos do CRM (Spec 119) — vistos nas colunas Plano/Última interação. */
    record LeadEnrichment(Instant lastInteractionAt, String lastPlanName) {
    }
}
