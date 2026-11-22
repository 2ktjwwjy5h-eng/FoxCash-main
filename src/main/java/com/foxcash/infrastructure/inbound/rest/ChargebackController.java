package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.QueryChargebackUseCase;
import com.foxcash.domain.port.in.RegisterChargebackUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.ChargebackResponse;
import com.foxcash.infrastructure.inbound.rest.dto.MasterRegisterChargebackRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MerchantRegisterChargebackRequest;
import com.foxcash.infrastructure.security.MerchantAccessGuard;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Adaptador REST de chargebacks (Spec 19).
 *
 * <p>Registro: o master cadastra notificacoes do PSP
 * ({@code POST /api/v1/master/chargebacks}) e o lojista/bot reporta disputas
 * do comprador ({@code POST /api/v1/merchants/{merchantId}/chargebacks}, com
 * {@code X-Api-Key} + guarda de posse). Listagem recente: master.
 */
@RestController
@RequestMapping("/api/v1")
public class ChargebackController {

    private final RegisterChargebackUseCase registerChargeback;
    private final QueryChargebackUseCase queryChargeback;
    private final MerchantAccessGuard accessGuard;

    public ChargebackController(RegisterChargebackUseCase registerChargeback,
                                QueryChargebackUseCase queryChargeback,
                                MerchantAccessGuard accessGuard) {
        this.registerChargeback = registerChargeback;
        this.queryChargeback = queryChargeback;
        this.accessGuard = accessGuard;
    }

    @PostMapping("/master/chargebacks")
    public ResponseEntity<ChargebackResponse> registerByMaster(
            @Valid @RequestBody MasterRegisterChargebackRequest request) {
        RegisterChargebackUseCase.Result result = registerChargeback.register(
                request.merchantId(), request.transactionId(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ChargebackResponse.from(queryChargeback.findById(result.chargebackId())));
    }

    @PostMapping("/merchants/{merchantId}/chargebacks")
    public ResponseEntity<ChargebackResponse> registerByMerchant(
            @PathVariable UUID merchantId,
            @Valid @RequestBody MerchantRegisterChargebackRequest request) {
        accessGuard.requireMerchantAccess(merchantId);
        RegisterChargebackUseCase.Result result = registerChargeback.register(
                merchantId, request.transactionId(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ChargebackResponse.from(queryChargeback.findById(result.chargebackId())));
    }

    @GetMapping("/master/chargebacks")
    public List<ChargebackResponse> recent(
            @RequestParam(defaultValue = "50") int limit) {
        return queryChargeback.findRecent(Math.min(Math.max(limit, 1), 100)).stream()
                .map(ChargebackResponse::from)
                .toList();
    }
}
