package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.PortalGatewayService;
import com.foxcash.domain.model.profile.MerchantProfile;
import com.foxcash.domain.port.out.MerchantProfileRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayCatalogItemResponse;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayOverviewResponse;
import com.foxcash.infrastructure.inbound.rest.dto.GatewayProviderResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PortalGatewayController} (Spec 107): delegação para o
 * service com o perfil ativo do header (Spec 139) e catálogo global.
 */
class PortalGatewayControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final PortalGatewayService gatewayService = mock(PortalGatewayService.class);
    private final MerchantProfileRepository profileRepository = mock(MerchantProfileRepository.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final Authentication authentication = mock(Authentication.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private PortalGatewayController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalGatewayController(gatewayService,
                new ProfileScopeResolver(profileRepository, botRepository));
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    @Test
    void overviewDelegatesWithActiveProfileFromHeader() {
        UUID profileId = UUID.randomUUID();
        MerchantProfile profile = mock(MerchantProfile.class);
        when(profile.id()).thenReturn(profileId);
        when(request.getHeader("X-Profile-ID")).thenReturn(profileId.toString());
        when(profileRepository.findByIdAndMerchantId(profileId, MERCHANT_ID))
                .thenReturn(Optional.of(profile));
        when(gatewayService.overview(MERCHANT_ID, profileId)).thenReturn(new GatewayOverviewResponse(List.of()));

        GatewayOverviewResponse response = controller.overview(authentication, request);

        verify(gatewayService).overview(MERCHANT_ID, profileId);
        assertThat(response.providers()).isEmpty();
    }

    @Test
    void overviewWithoutHeaderFallsBackToRiskRoute() {
        when(request.getHeader("X-Profile-ID")).thenReturn(null);
        when(gatewayService.overview(MERCHANT_ID, null))
                .thenReturn(new GatewayOverviewResponse(List.of(
                        new GatewayProviderResponse("pagarme", "Pagar.me", 1, "CLOSED",
                                new BigDecimal("10.00"), 1L))));

        GatewayOverviewResponse response = controller.overview(authentication, request);

        verify(gatewayService).overview(MERCHANT_ID, null);
        assertThat(response.providers()).hasSize(1);
        assertThat(response.providers().get(0).provider()).isEqualTo("pagarme");
    }

    @Test
    void catalogDelegatesGlobally() {
        when(gatewayService.catalog()).thenReturn(List.of(
                new GatewayCatalogItemResponse("pagarme", "Pagar.me", "PIX", true, false)));

        List<GatewayCatalogItemResponse> catalog = controller.catalog();

        verify(gatewayService).catalog();
        assertThat(catalog).hasSize(1);
        assertThat(catalog.get(0).id()).isEqualTo("pagarme");
    }
}
