package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PspBalanceProvider;
import com.foxcash.infrastructure.config.PspProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Saldo fake da conta master (Spec 92 — dev/test): devolve o valor configurado
 * {@code foxcash.psp.mock-balance} (default 0) para o job de sweep operar sem
 * adquirente real. Registrado quando {@code foxcash.psp.provider=mock}.
 */
@Component
@ConditionalOnProperty(prefix = "foxcash.psp", name = "provider",
        havingValue = "mock", matchIfMissing = true)
public class MockPspBalanceProvider implements PspBalanceProvider {

    private final PspProperties pspProperties;

    public MockPspBalanceProvider(PspProperties pspProperties) {
        this.pspProperties = pspProperties;
    }

    @Override
    public PspBalance getBalance() {
        return new PspBalance(pspProperties.getMockBalance(), BigDecimal.ZERO, "BRL");
    }
}
