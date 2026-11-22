package com.foxcash.application.service;

import com.foxcash.domain.model.transaction.ProviderVolume;
import com.foxcash.domain.port.out.TransactionRepository;
import com.foxcash.infrastructure.config.ProviderCatalog;
import com.foxcash.infrastructure.config.PspProperties;
import com.foxcash.infrastructure.config.RoutingProperties;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayCatalogItemResponse;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayOverviewResponse;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayProviderResponse;
import com.foxcash.infrastructure.outbound.pix.PixProviderHealthRegistry;
import com.foxcash.infrastructure.outbound.pix.RoutingPolicyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PortalGatewayService} (Spec 107): ordem de fallback da
 * policy, circuito ausente → CLOSED, extrato por provider e catálogo
 * available/configured.
 */
class PortalGatewayServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();

    private final RoutingPolicyResolver routingPolicyResolver = mock(RoutingPolicyResolver.class);
    private final PixProviderHealthRegistry healthRegistry = mock(PixProviderHealthRegistry.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final ProviderCatalog providerCatalog = new ProviderCatalog(new PspProperties(), new RoutingProperties());

    private PortalGatewayService service;

    @BeforeEach
    void setUp() {
        service = new PortalGatewayService(routingPolicyResolver, healthRegistry, transactionRepository, providerCatalog);
        when(healthRegistry.stateName("pagarme")).thenReturn("CLOSED");
        when(healthRegistry.stateName("asaas")).thenReturn("OPEN");
        when(healthRegistry.stateName("mock")).thenReturn("HALF_OPEN");
    }

    @Test
    void overviewMapsOrderCircuitStateAndVolumeByProvider() {
        when(routingPolicyResolver.resolveProviders(MERCHANT_ID, PROFILE_ID))
                .thenReturn(List.of("pagarme", "asaas", "mock"));
        when(transactionRepository.sumAndCountApprovedByProvider(
                org.mockito.ArgumentMatchers.eq(MERCHANT_ID),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new ProviderVolume("pagarme", new BigDecimal("293.58"), 42L)));

        GatewayOverviewResponse response = service.overview(MERCHANT_ID, PROFILE_ID);

        assertThat(response.providers()).hasSize(3);
        GatewayProviderResponse first = response.providers().get(0);
        assertThat(first.provider()).isEqualTo("pagarme");
        assertThat(first.displayName()).isEqualTo("Pagar.me");
        assertThat(first.order()).isEqualTo(1);
        assertThat(first.circuitState()).isEqualTo("CLOSED");
        assertThat(first.volumePeriod()).isEqualByComparingTo("293.58");
        assertThat(first.countPeriod()).isEqualTo(42);

        GatewayProviderResponse asaas = response.providers().get(1);
        assertThat(asaas.displayName()).isEqualTo("Asaas");
        assertThat(asaas.order()).isEqualTo(2);
        assertThat(asaas.circuitState()).isEqualTo("OPEN");
        assertThat(asaas.volumePeriod()).isEqualByComparingTo("0");
        assertThat(asaas.countPeriod()).isZero();

        GatewayProviderResponse mock = response.providers().get(2);
        assertThat(mock.order()).isEqualTo(3);
        assertThat(mock.circuitState()).isEqualTo("HALF_OPEN");
    }

    @Test
    void overviewResolvesByActiveProfile() {
        when(routingPolicyResolver.resolveProviders(MERCHANT_ID, PROFILE_ID))
                .thenReturn(List.of("pagarme"));

        service.overview(MERCHANT_ID, PROFILE_ID);

        verify(routingPolicyResolver).resolveProviders(MERCHANT_ID, PROFILE_ID);
    }

    @Test
    void overviewWithNoProvidersReturnsEmptyList() {
        when(routingPolicyResolver.resolveProviders(MERCHANT_ID, null)).thenReturn(List.of());

        GatewayOverviewResponse response = service.overview(MERCHANT_ID, null);

        assertThat(response.providers()).isEmpty();
    }

    @Test
    void catalogMarksPixAdaptersAvailableAndConfigured() {
        // providerCatalog default: PADRAO only (sem providers configurados).
        List<GatewayCatalogItemResponse> catalog = service.catalog();

        assertThat(catalog).hasSize(5);
        GatewayCatalogItemResponse pagarme = catalog.stream()
                .filter(i -> i.id().equals("pagarme")).findFirst().orElseThrow();
        assertThat(pagarme.category()).isEqualTo("PIX");
        assertThat(pagarme.available()).isTrue();
        assertThat(pagarme.configured()).isFalse();
        assertThat(pagarme.name()).isEqualTo("Pagar.me");

        GatewayCatalogItemResponse cielo = catalog.stream()
                .filter(i -> i.id().equals("cielo")).findFirst().orElseThrow();
        assertThat(cielo.category()).isEqualTo("CARTÃO");
        assertThat(cielo.available()).isFalse();
        assertThat(cielo.configured()).isFalse();

        GatewayCatalogItemResponse bitpay = catalog.stream()
                .filter(i -> i.id().equals("bitpay")).findFirst().orElseThrow();
        assertThat(bitpay.category()).isEqualTo("CRIPTO");
        assertThat(bitpay.available()).isFalse();
    }
}
