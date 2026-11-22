package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.port.out.PspBalanceProvider;
import com.foxcash.infrastructure.config.PspProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link MockPspBalanceProvider} (Spec 92): devolve o saldo fake
 * configurado ({@code foxcash.psp.mock-balance}, default 0) — o job de sweep
 * opera em dev/test sem adquirente real. Herda o contrato
 * {@link PspBalanceContractTest} (available não-nulo + currency BRL).
 */
class MockPspBalanceProviderTest extends PspBalanceContractTest {

    @Override
    protected PspBalanceProvider client() {
        return new MockPspBalanceProvider(new PspProperties());
    }

    @Test
    void devolveValorConfigurado() {
        PspProperties properties = new PspProperties();
        properties.setMockBalance(new BigDecimal("1234.56"));

        PspBalanceProvider.PspBalance balance =
                new MockPspBalanceProvider(properties).getBalance();

        assertThat(balance.available()).isEqualByComparingTo("1234.56");
        assertThat(balance.blocked()).isEqualByComparingTo("0");
        assertThat(balance.currency()).isEqualTo("BRL");
    }

    @Test
    void devolveZeroPorDefault() {
        PspBalanceProvider.PspBalance balance =
                new MockPspBalanceProvider(new PspProperties()).getBalance();

        assertThat(balance.available()).isEqualByComparingTo("0");
    }
}
