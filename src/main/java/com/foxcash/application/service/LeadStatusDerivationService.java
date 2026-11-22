package com.foxcash.application.service;

import com.foxcash.domain.model.botfunnel.BotLead;
import com.foxcash.domain.model.crm.CrmLeadStatus;
import com.foxcash.domain.port.out.BotLeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Function;

/**
 * Derivação do status do lead no CRM (Spec 83 D4) — chamado NA MESMA
 * transação dos eventos do funil (Spec 75): {@code recordLead → NOVO},
 * {@code recordCheckout → PENDENTE} (se não PAGO/BLOQUEADO),
 * {@code recordPaid → PAGO} (se não BLOQUEADO); bloqueio (D6) → {@code
 * BLOQUEADO} (sobrescreve). Nunca re-scan em batch — consistência imediata.
 */
@Service
public class LeadStatusDerivationService {

    private final BotLeadRepository leadRepository;

    public LeadStatusDerivationService(BotLeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    /** {@code checkout_started} → {@code PENDENTE} (Spec 83 D4). */
    @Transactional
    public void onCheckoutStarted(UUID botId, String platformUserId) {
        update(botId, platformUserId, lead ->
                (lead.status() == CrmLeadStatus.PAGO || lead.status() == CrmLeadStatus.BLOQUEADO)
                        ? lead : lead.withCrmStatus(CrmLeadStatus.PENDENTE));
    }

    /** {@code paid} → {@code PAGO} (Spec 83 D4) — bloqueado permanece. */
    @Transactional
    public void onPaid(UUID botId, String platformUserId) {
        update(botId, platformUserId, lead ->
                lead.status() == CrmLeadStatus.BLOQUEADO
                        ? lead : lead.withCrmStatus(CrmLeadStatus.PAGO));
    }

    /** Bloqueio (D6) → {@code BLOQUEADO} (sobrescreve qualquer status). */
    @Transactional
    public void markBlocked(UUID botId, String platformUserId) {
        update(botId, platformUserId, lead -> lead.withCrmStatus(CrmLeadStatus.BLOQUEADO));
    }

    private void update(UUID botId, String platformUserId, Function<BotLead, BotLead> transition) {
        leadRepository.findByBotIdAndPlatformUserId(botId, platformUserId)
                .ifPresent(lead -> {
                    BotLead updated = transition.apply(lead);
                    if (updated != lead) {
                        leadRepository.save(updated);
                    }
                });
    }
}
