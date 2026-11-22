package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.CrmLeadService;
import com.foxcash.application.service.DiscordBotManager;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.crm.LeadHandoffStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.BotLeadRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: interação de usuário num bot (Spec 75/D6) — o renderizador
 * (bots/, stateless) reporta a chegada de interação via
 * {@code POST /api/v1/foxcash/bots/{id}/interaction} (API key do lojista,
 * {@code ROLE_MERCHANT}). O backend registra o lead (usuário único que inicia
 * conversa) — idempotente.
 *
 * <p>Spec 90 (handoff): se o lead está em {@code HUMAN}, a mensagem (com texto
 * opcional no payload — decidido na implementação, Spec 83 D7) é consumida
 * para o CRM (persiste CLIENT + SSE) e a interação não é encaminhada ao funil
 * do bot Discord.
 *
 * <p>IDOR-safe: o bot pertence ao lojista da API key (404 {@code bot_not_found}
 * cross-lojista, sem revelar existência). Nenhum dado pessoal além do ID opaco
 * da plataforma ({@code platformUserId}) é capturado.
 */
@RestController
@RequestMapping("/api/v1/foxcash/bots")
public class FoxBotInteractionController {

    private final StoreBotRepository botRepository;
    private final DiscordBotManager discordBotManager;
    private final BotLeadRepository botLeadRepository;
    private final CrmLeadService crmLeadService;

    public FoxBotInteractionController(StoreBotRepository botRepository,
                                       DiscordBotManager discordBotManager,
                                       BotLeadRepository botLeadRepository,
                                       CrmLeadService crmLeadService) {
        this.botRepository = botRepository;
        this.discordBotManager = discordBotManager;
        this.botLeadRepository = botLeadRepository;
        this.crmLeadService = crmLeadService;
    }

    @PostMapping("/{id}/interaction")
    public ResponseEntity<Void> interaction(Authentication authentication,
                                            @PathVariable UUID id,
                                            @RequestBody InteractionRequest request) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        StoreBot bot = botRepository.findByIdAndMerchantId(id, merchantId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(StoreBotNotFoundException::new);
        // Registra o lead de forma idempotente (UNIQUE bot+user).
        discordBotManager.recordInteraction(bot.id(), request.platformUserId());
        // Spec 90: lead em HUMAN → persiste CLIENT + SSE (o funil não processa).
        botLeadRepository.findByBotIdAndPlatformUserId(bot.id(), request.platformUserId())
                .filter(lead -> lead.handoffStatus() == LeadHandoffStatus.HUMAN)
                .ifPresent(lead -> crmLeadService.persistInbound(merchantId, bot.id(),
                        request.platformUserId(), request.text(), null));
        return ResponseEntity.noContent().build();
    }

    /** {@code platformUserId} é o ID do usuário na plataforma (opaco, nunca PII). */
    public record InteractionRequest(String platformUserId, String text) {
    }
}
