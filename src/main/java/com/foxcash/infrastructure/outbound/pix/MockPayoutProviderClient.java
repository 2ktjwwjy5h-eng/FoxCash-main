package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Implementacao mock do PIX de saida (Spec 36) para desenvolvimento.
 *
 * <p>Aceita o payout e devolve uma referencia externa fake (formato
 * {@code PO<32 hex>}); simula falha sincrona (rollback do saque) quando
 * {@code foxcash.psp.mock-payout-failure=true}. {@code getPayout} devolve
 * {@code PAID} (ou {@code FAILED} com a falha simulada) para a reconciliacao.
 *
 * <p>Registrado SEMPRE como bean (Spec 92 — o {@code PayoutProviderRouter} roteia
 * por prioridade entre os provedores; a seleção exclusiva por
 * {@code payout-provider} deixou de ser por conditional e virou decisão do
 * router). Em dev o router usa {@code payout-provider=mock} (default); o
 * fail-fast de credencial continua no {@code PspProperties.validate()}.
 */
@Component
public class MockPayoutProviderClient implements PayoutProviderClient {

    private static final Logger log = LoggerFactory.getLogger(MockPayoutProviderClient.class);

    private final PspProperties pspProperties;

    public MockPayoutProviderClient(PspProperties pspProperties) {
        this.pspProperties = pspProperties;
    }

    @Override
    public PayoutResult createPayout(PayoutCommand command) {
        if (pspProperties.isMockPayoutFailure()) {
            log.warn("Mock payout: falha simulada (foxcash.psp.mock-payout-failure=true) "
                    + "para saque={} — rollback da solicitacao", command.withdrawalId());
            // Contrato da porta (Spec 36): falha sincrona do PSP => PayoutProviderException.
            throw new PayoutProviderException("Mock payout: falha simulada no PSP");
        }
        // Referencia fake no formato PO<32 hex> (distingue do cobranca E<32 hex>).
        String externalReference = "PO" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        log.info("Mock payout: saque={} aceito, externalReference={}",
                command.withdrawalId(), externalReference);
        return new PayoutResult(externalReference);
    }

    @Override
    public PayoutStatus getPayout(String externalReference) {
        // Reconciacao: falha simulada => FAILED; senao PAID.
        return pspProperties.isMockPayoutFailure() ? PayoutStatus.FAILED : PayoutStatus.PAID;
    }
}
