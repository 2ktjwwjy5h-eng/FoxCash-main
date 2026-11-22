package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.in.MerchantConfigUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.BanRequest;
import com.foxcash.infrastructure.inbound.rest.dto.DebitRequest;
import com.foxcash.infrastructure.inbound.rest.dto.HoneypotRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantConfigRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantConfigResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Adaptador REST: config fina por lojista + controle de conta (Spec 98 — D5).
 * Rotas ADITIVAS sob {@code ROLE_MASTER} ({@code GET/PUT .../merchants/{id}/
 * config} não conflitam com o painel de leitura da Spec 97, rotas distintas).
 *
 * <p>Escrita com RBAC (Spec 69) + auditoria (Spec 37) no service; debit e ban
 * exigem step-up TOTP (403 {@code step_up_required} sem janela válida).
 */
@RestController
@RequestMapping("/api/v1/master")
public class MasterConfigController {

    private final MerchantConfigUseCase configUseCase;
    private final ClientIpResolver clientIpResolver;

    public MasterConfigController(MerchantConfigUseCase configUseCase,
                                  ClientIpResolver clientIpResolver) {
        this.configUseCase = configUseCase;
        this.clientIpResolver = clientIpResolver;
    }

    /** Config atual do lojista (nulls = sem override → default global). */
    @GetMapping("/merchants/{merchantId}/config")
    public MerchantConfigResponse getConfig(@PathVariable UUID merchantId) {
        return configUseCase.getConfig(merchantId);
    }

    /** Atualização PARCIAL (null = não alterar — PATCH semantics). */
    @PutMapping("/merchants/{merchantId}/config")
    public MerchantConfigResponse updateConfig(@PathVariable UUID merchantId,
                                               @RequestBody MerchantConfigRequest request,
                                               Authentication authentication,
                                               HttpServletRequest httpRequest) {
        return configUseCase.updateConfig(merchantId, request,
                (UUID) authentication.getPrincipal(),
                clientIpResolver.resolve(httpRequest));
    }

    /** Débito administrativo do wallet (ação sensível — step-up TOTP). */
    @PostMapping("/merchants/{merchantId}/config/debit")
    public ResponseEntity<DebitOutcomeResponse> debit(@PathVariable UUID merchantId,
                                                      @RequestBody DebitRequest request,
                                                      Authentication authentication,
                                                      HttpServletRequest httpRequest) {
        MerchantConfigUseCase.DebitOutcome outcome = configUseCase.debit(merchantId,
                Money.of(request.amount()), request.reason(),
                (UUID) authentication.getPrincipal(),
                clientIpResolver.resolve(httpRequest));
        return ResponseEntity.ok(new DebitOutcomeResponse(
                outcome.newBalance().amount(), outcome.debitedAmount().amount()));
    }

    /** Marca/desmarca honeypot (ambiente controlado; reversível). */
    @PostMapping("/merchants/{merchantId}/config/honeypot")
    public ResponseEntity<Void> honeypot(@PathVariable UUID merchantId,
                                         @RequestBody HoneypotRequest request,
                                         Authentication authentication,
                                         HttpServletRequest httpRequest) {
        configUseCase.markHoneypot(merchantId, request.enabled(),
                (UUID) authentication.getPrincipal(),
                clientIpResolver.resolve(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** Kill switch: desativa (INACTIVE — bloqueia portal/API/saques). */
    @PostMapping("/merchants/{merchantId}/config/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID merchantId,
                                           Authentication authentication,
                                           HttpServletRequest httpRequest) {
        configUseCase.deactivate(merchantId,
                (UUID) authentication.getPrincipal(),
                clientIpResolver.resolve(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** Kill switch: ativa (ACTIVE). */
    @PostMapping("/merchants/{merchantId}/config/activate")
    public ResponseEntity<Void> activate(@PathVariable UUID merchantId,
                                         Authentication authentication,
                                         HttpServletRequest httpRequest) {
        configUseCase.activate(merchantId,
                (UUID) authentication.getPrincipal(),
                clientIpResolver.resolve(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** Banimento irreversível (BANNED — ação sensível com step-up TOTP). */
    @PostMapping("/merchants/{merchantId}/ban")
    public ResponseEntity<Void> ban(@PathVariable UUID merchantId,
                                    @RequestBody BanRequest request,
                                    Authentication authentication,
                                    HttpServletRequest httpRequest) {
        configUseCase.ban(merchantId, request.reason(),
                (UUID) authentication.getPrincipal(),
                clientIpResolver.resolve(httpRequest));
        return ResponseEntity.noContent().build();
    }

    /** Resposta do débito: saldo após a mutação + valor debitado (Spec 98 — D5). */
    public record DebitOutcomeResponse(BigDecimal newBalance, BigDecimal debitedAmount) {
    }
}
