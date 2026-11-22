package com.foxcash.infrastructure.outbound.persistence.mapper;

import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.botfunnel.UtmParams;
import com.foxcash.domain.model.crm.CrmLeadStatus;
import com.foxcash.domain.model.crm.LeadHandoffStatus;
import com.foxcash.infrastructure.outbound.persistence.entity.BotLeadJpaEntity;

/**
 * Mapeia entre o modelo de domínio {@link BotLead} e a entidade JPA
 * {@link BotLeadJpaEntity} (Spec 75). UTM (Spec 81/D4) e CRM (Specs 83/90) —
 * colunas anuláveis com defaults (status NOVO, handoff BOT).
 */
public final class BotLeadMapper {

    private BotLeadMapper() {
    }

    public static BotLeadJpaEntity toEntity(BotLead lead) {
        UtmParams utm = lead.utm();
        return new BotLeadJpaEntity(lead.id(), lead.botId(), lead.platformUserId(),
                lead.firstSeenAt(), lead.createdAt(),
                utm == null ? null : utm.source(),
                utm == null ? null : utm.medium(),
                utm == null ? null : utm.campaign(),
                utm == null ? null : utm.content(),
                utm == null ? null : utm.term(),
                lead.name(), lead.username(), lead.email(), lead.phone(),
                lead.status().name(), lead.handoffStatus().name(), null);
    }

    public static BotLead toDomain(BotLeadJpaEntity entity) {
        UtmParams utm = entity.getUtmSource() == null && entity.getUtmMedium() == null
                && entity.getUtmCampaign() == null && entity.getUtmContent() == null
                && entity.getUtmTerm() == null
                ? null
                : UtmParams.of(entity.getUtmSource(), entity.getUtmMedium(),
                        entity.getUtmCampaign(), entity.getUtmContent(), entity.getUtmTerm());
        return BotLead.restoreCrm(entity.getId(), entity.getBotId(), entity.getPlatformUserId(),
                entity.getFirstSeenAt(), entity.getCreatedAt(), utm,
                entity.getName(), entity.getUsername(), entity.getEmail(), entity.getPhone(),
                entity.getStatus() == null ? CrmLeadStatus.NOVO : CrmLeadStatus.valueOf(entity.getStatus()),
                entity.getHandoffStatus() == null
                        ? LeadHandoffStatus.BOT : LeadHandoffStatus.valueOf(entity.getHandoffStatus()));
    }
}
