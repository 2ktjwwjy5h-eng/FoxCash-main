package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.BotMetricsService;
import com.foxcash.domain.model.botfunnel.BotMetrics;
import com.foxcash.infrastructure.inbound.rest.dto.BotMetricsAggregateResponse;
import com.foxcash.infrastructure.inbound.rest.dto.BotMetricsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: espelho READ-ONLY das métricas de bots no Master Console
 * (Spec 75/D7) — {@code ROLE_MASTER}, RBAC Spec 69 (leitura = OPERATOR). Todos
 * os lojistas, com filtros opcionais ({@code merchantId}, {@code from},
 * {@code to}); período default 30d; {@code receita} em escala normalizada; sem
 * PII (só contagens/agregados).
 */
@RestController
@RequestMapping("/api/v1/master/bots/metrics")
public class MasterBotMetricsController {

    private final BotMetricsService metricsService;

    public MasterBotMetricsController(BotMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping
    public BotMetricsAggregateResponse metrics(@RequestParam(required = false) String merchantId,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        UUID merchant = QueryParamParser.parseUuid(merchantId, "merchantId");
        Instant fromInstant = QueryParamParser.parseInstant(from, "from");
        Instant toInstant = QueryParamParser.parseInstant(to, "to");
        BotMetricsService.MerchantBotMetrics agg =
                metricsService.summarizeAll(fromInstant, toInstant, merchant);
        List<BotMetricsResponse> bots = agg.bots().stream()
                .map(v -> BotMetricsResponse.of(v.botId(), v.merchantId(), v.merchantName(),
                        v.metrics().leads(), v.metrics().vendas(),
                        v.metrics().receita().amount(), v.metrics().conversao()))
                .toList();
        BotMetrics t = agg.total();
        return new BotMetricsAggregateResponse(bots,
                BotMetricsAggregateResponse.BotMetricsSummary.of(
                        t.leads(), t.vendas(), t.receita().amount(), t.conversao()));
    }
}
