package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.BotMetricsService;
import com.foxcash.domain.exception.StoreBotNotFoundException;
import com.foxcash.domain.model.botfunnel.BotMetrics;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.infrastructure.inbound.rest.dto.BotMetricsAggregateResponse;
import com.foxcash.infrastructure.inbound.rest.dto.BotMetricsResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: métricas de bots no portal (Spec 75/D7), {@code ROLE_MERCHANT_PORTAL}
 * (principal = merchantId). Escopo por lojista — bot de outro lojista → 404
 * {@code bot_not_found} (sem revelar existência). Período default 30d
 * ({@code from}/{@code to} ISO-8601 opcionais); {@code receita} em escala
 * normalizada; sem PII (só contagens/agregados).
 */
@RestController
@RequestMapping("/api/v1/portal/bots")
public class PortalBotMetricsController {

    private final StoreBotRepository botRepository;
    private final BotMetricsService metricsService;
    private final ProfileScopeResolver scopeResolver;

    public PortalBotMetricsController(StoreBotRepository botRepository,
                                      BotMetricsService metricsService,
                                      ProfileScopeResolver scopeResolver) {
        this.botRepository = botRepository;
        this.metricsService = metricsService;
        this.scopeResolver = scopeResolver;
    }

    /** Métricas de UM bot do lojista — 404 cross-lojista. */
    @GetMapping("/{id}/metrics")
    public BotMetricsResponse metrics(Authentication authentication,
                                      @PathVariable UUID id,
                                      @RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        StoreBot bot = botRepository.findByIdAndMerchantId(id, merchantId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(StoreBotNotFoundException::new);
        Instant fromInstant = QueryParamParser.parseInstant(from, "from");
        Instant toInstant = QueryParamParser.parseInstant(to, "to");
        BotMetrics m = metricsService.summarize(bot.id(), fromInstant, toInstant);
        return BotMetricsResponse.of(bot.id(), null, null, m.leads(), m.vendas(),
                m.receita().amount(), m.conversao());
    }

    /** Métricas agregadas da loja (lista por bot + total). */
    @GetMapping("/metrics")
    public BotMetricsAggregateResponse merchantMetrics(Authentication authentication,
                                                       HttpServletRequest httpRequest,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        Instant fromInstant = QueryParamParser.parseInstant(from, "from");
        Instant toInstant = QueryParamParser.parseInstant(to, "to");
        // Spec 124 (bot-set): null → todos os bots do lojista (sem header);
        // perfil ativo → só os bots do perfil; header inválido → vazio.
        BotMetricsService.MerchantBotMetrics agg = metricsService.summarizeMerchant(
                merchantId, scopeResolver.scopeBotIds(merchantId, httpRequest),
                fromInstant, toInstant);
        List<BotMetricsResponse> bots = agg.bots().stream()
                .map(v -> BotMetricsResponse.of(v.botId(), null, null,
                        v.metrics().leads(), v.metrics().vendas(),
                        v.metrics().receita().amount(), v.metrics().conversao()))
                .toList();
        BotMetrics t = agg.total();
        return new BotMetricsAggregateResponse(bots,
                BotMetricsAggregateResponse.BotMetricsSummary.of(
                        t.leads(), t.vendas(), t.receita().amount(), t.conversao()));
    }
}
