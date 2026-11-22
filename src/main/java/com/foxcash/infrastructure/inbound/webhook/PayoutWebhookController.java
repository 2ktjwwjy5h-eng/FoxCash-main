package com.foxcash.infrastructure.inbound.webhook;

import com.foxcash.application.service.WithdrawalSettlementService;
import com.foxcash.infrastructure.inbound.rest.dto.PayoutWebhookRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada: recebe callbacks do provedor de PIX de saida (Spec 36)
 * — {@code POST /webhooks/payouts} (assinatura HMAC no padrao do
 * {@code WebhookSignatureFilter}, segredo {@code foxcash.psp.payout-webhook.*}).
 */
@RestController
@RequestMapping("/webhooks/payouts")
public class PayoutWebhookController {

    private final WithdrawalSettlementService settlement;

    public PayoutWebhookController(WithdrawalSettlementService settlement) {
        this.settlement = settlement;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@Valid @RequestBody PayoutWebhookRequest request) {
        settlement.settleByExternalReference(request.externalReference(), request.status(),
                request.failedReason());
        return ResponseEntity.noContent().build();
    }
}
