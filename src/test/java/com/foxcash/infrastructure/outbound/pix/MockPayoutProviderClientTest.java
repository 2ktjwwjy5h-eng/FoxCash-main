package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.port.out.PayoutProviderClient;
import com.foxcash.infrastructure.config.PspProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link MockPayoutProviderClient} (Spec 36/38): estende o
 * {@link PayoutProviderContractTest} (contrato do adapter — invariantes
 * verificados automaticamente) e cobre os comportamentos específicos do mock
 * ({@code mock-payout-failure=true} → falha síncrona no aceite e {@code FAILED}
 * na consulta da reconciliação).
 */
class MockPayoutProviderClientTest extends PayoutProviderContractTest {

    @Override
    protected PayoutProviderClient client() {
        return new MockPayoutProviderClient(new PspProperties());
    }

    @Override
    protected PayoutProviderClient failingClient() {
        PspProperties properties = new PspProperties();
        properties.setMockPayoutFailure(true);
        return new MockPayoutProviderClient(properties);
    }

    @Override
    protected PayoutProviderClient.PayoutCommand command() {
        return new PayoutProviderClient.PayoutCommand(
                UUID.randomUUID(), Money.of("40.00"),
                new PayoutProviderClient.PayoutDestination("52998224725", null, null, null, null),
                "teste");
    }

    @Test
    void acceptsPayoutAndReturnsExternalReference() {
        PspProperties properties = new PspProperties();
        MockPayoutProviderClient mock = new MockPayoutProviderClient(properties);

        PayoutProviderClient.PayoutResult result = mock.createPayout(command());

        assertThat(result.externalReference()).startsWith("PO");
        assertThat(result.externalReference()).hasSize(34); // PO + 32 hex
    }

    @Test
    void throwsWhenMockPayoutFailureEnabled() {
        PspProperties properties = new PspProperties();
        properties.setMockPayoutFailure(true);
        MockPayoutProviderClient mock = new MockPayoutProviderClient(properties);

        // Contrato da porta (Spec 36/92): falha sincrona do PSP =>
        // PayoutProviderException (o PayoutProviderRouter a captura para fallback).
        assertThatThrownBy(() -> mock.createPayout(command()))
                .isInstanceOf(PayoutProviderClient.PayoutProviderException.class);
    }

    @Test
    void getPayoutReflectsFailureFlag() {
        PspProperties properties = new PspProperties();
        MockPayoutProviderClient mock = new MockPayoutProviderClient(properties);
        assertThat(mock.getPayout("PO1")).isEqualTo(PayoutProviderClient.PayoutStatus.PAID);

        properties.setMockPayoutFailure(true);
        assertThat(mock.getPayout("PO1")).isEqualTo(PayoutProviderClient.PayoutStatus.FAILED);
    }
}
