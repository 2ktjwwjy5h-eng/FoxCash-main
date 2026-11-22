package com.foxcash.application.service;

import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.crm.LeadHandoffStatus;
import com.foxcash.domain.exception.LeadNotFoundException;
import com.foxcash.domain.port.out.BotLeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Handoff humano no CRM (Spec 90) — alterna o atendimento de UM lead entre
 * {@code BOT} (automático) e {@code HUMAN} (manual). Transições idempotentes no
 * domínio ({@link BotLead#pauseBot()}/{@link BotLead#resumeBot()}); auditoria
 * {@code LEAD_HANDOFF} (Spec 41) com before/after na MESMA transação.
 */
@Service
public class LeadHandoffService {

    private final BotLeadRepository leadRepository;
    private final PlatformActivityRecorder activityRecorder;

    public LeadHandoffService(BotLeadRepository leadRepository,
                              PlatformActivityRecorder activityRecorder) {
        this.leadRepository = leadRepository;
        this.activityRecorder = activityRecorder;
    }

    /**
     * Define o modo de atendimento do lead (Spec 90 C6). Lead de outro lojista
     * → 404 (cross-lojista, padrão Spec 83). Modo inválido é validado pelo
     * controller (400). Repetir o mesmo estado é no-op sem erro (idempotente).
     */
    @Transactional
    public BotLead setHandoff(UUID merchantId, UUID leadId, LeadHandoffStatus mode) {
        BotLead lead = leadRepository.findByIdAndMerchantId(merchantId, leadId)
                .orElseThrow(() -> new LeadNotFoundException("Lead nao encontrado"));
        BotLead updated = mode == LeadHandoffStatus.HUMAN ? lead.pauseBot() : lead.resumeBot();
        if (updated != lead) {
            leadRepository.save(updated);
            activityRecorder.record(PlatformActorType.MERCHANT, merchantId, PlatformAction.LEAD_HANDOFF,
                    "LEAD", leadId, merchantId,
                    Map.of("leadId", leadId.toString(),
                            "before", lead.handoffStatus().name(),
                            "after", updated.handoffStatus().name()));
        }
        return updated;
    }
}
