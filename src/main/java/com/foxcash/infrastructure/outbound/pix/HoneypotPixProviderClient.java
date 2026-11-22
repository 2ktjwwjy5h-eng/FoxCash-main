package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PixProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Provedor TRAP de cobrança para lojista honeypot (Spec 98 — D4.5). O atacante
 * da conta em ambiente controlado segue VENDO a cobrança funcionar (QR fictício
 * emitido) mas NENHUM crédito real existe: {@code createCharge} devolve um QR
 * plausível com {@code externalReference} {@code HP<uuid>} e **nunca** dispara
 * webhook PAID (nenhum {@code SALE_CREDIT}). {@code getChargeByCode} → NOT_FOUND
 * (não revela a cobrança — decisão registrada na implementação).
 *
 * <p>Bean condicional ({@code foxcash.psp.honeypot-enabled}, default ON): sem o
 * bean, o {@code PixProviderRouter} falha fechado (não há rota de honeypot).
 */
@Component
@ConditionalOnProperty(name = "foxcash.psp.honeypot-enabled", havingValue = "true", matchIfMissing = true)
public class HoneypotPixProviderClient implements PixProviderClient {

    private static final Logger log = LoggerFactory.getLogger(HoneypotPixProviderClient.class);

    public static final String PROVIDER_NAME = "honeypot";

    @Override
    public CreateChargeResult createCharge(CreateChargeCommand command) {
        String externalReference = "HP" + command.transactionId();
        // QR fictício (não escaneável em provedor real) — o atacante segue com a
        // ilusão de operação; nenhum PSP real é chamado (trap interno, Spec 98).
        String copyPaste = "00020126" + PROVIDER_NAME + externalReference;
        String qrCode = Base64.getEncoder().encodeToString(copyPaste.getBytes(StandardCharsets.UTF_8));
        log.warn("HoneypotPixProviderClient: cobranca TRAP criada para merchant={} transacao={} (nenhum PAID sera emitido)",
                command.merchantId(), command.transactionId());
        return new CreateChargeResult(externalReference, qrCode, copyPaste, PROVIDER_NAME);
    }

    @Override
    public GetChargeResult getChargeByCode(String code) {
        // NOT_FOUND: a cobrança trap não existe em PSP algum (decisão registrada —
        // não revela ao atacante e nunca confirma pagamento).
        return new GetChargeResult(false, null, null, null);
    }
}
