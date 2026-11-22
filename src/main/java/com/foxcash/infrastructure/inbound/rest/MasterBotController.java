package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.StoreBotQueryService;
import com.foxcash.infrastructure.inbound.rest.dto.MasterBotResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST: espelho READ-ONLY do registro de bots no Master Console
 * (Spec 74, critério 5) — {@code ROLE_MASTER}, RBAC Spec 69 (leitura =
 * OPERATOR). Sem ações de escrita (toda escrita fica no portal).
 */
@RestController
@RequestMapping("/api/v1/master/bots")
public class MasterBotController {

    private final StoreBotQueryService bots;

    public MasterBotController(StoreBotQueryService bots) {
        this.bots = bots;
    }

    @GetMapping
    public List<MasterBotResponse> list() {
        return bots.listAllForMaster().stream().map(MasterBotResponse::from).toList();
    }

    @GetMapping("/{id}")
    public MasterBotResponse get(@PathVariable UUID id) {
        return MasterBotResponse.from(bots.getForMaster(id));
    }
}
