package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.TransactionTraceService;
import com.foxcash.infrastructure.inbound.rest.dto.TransactionTraceResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trace de transação (Spec 125) — rastreio ponta a ponta para o suporte.
 *
 * <p>Read-only, ROLE_MASTER (rota sob {@code /api/v1/master/**} →
 * {@code hasRole("MASTER")} no SecurityConfig, padrão dos endpoints master de
 * leitura). Resolve {@code ref} como UUID interno, {@code externalReference}
 * (txid do PSP) ou {@code idempotencyKey} (do bot) e devolve a jornada
 * (Bot → Gateway → PSP → Ledger → Webhook).
 */
@RestController
@RequestMapping("/api/v1/master/trace")
public class MasterTraceController {

    private final TransactionTraceService traceService;

    public MasterTraceController(TransactionTraceService traceService) {
        this.traceService = traceService;
    }

    @GetMapping("/transactions/{ref}")
    public TransactionTraceResponse trace(@PathVariable String ref) {
        return TransactionTraceResponse.from(traceService.trace(ref));
    }
}
