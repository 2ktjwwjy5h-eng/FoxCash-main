package com.foxcash.infrastructure.inbound.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.application.service.PlanActionPayloadCodec;
import com.foxcash.domain.exception.PlanNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.plan.DeliveryAction;
import com.foxcash.domain.model.plan.DeliveryActionType;
import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.port.in.PlanManagementUseCase;
import com.foxcash.domain.port.in.PlanManagementUseCase.PlanView;
import com.foxcash.infrastructure.inbound.rest.dto.PlanActionRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PlanActionResponse;
import com.foxcash.infrastructure.inbound.rest.dto.PlanRequest;
import com.foxcash.infrastructure.inbound.rest.dto.PlanResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST de planos do portal (Spec 49): listagem com
 * hasDeliveryActions, create 201, actions com payload, 404 cross-loja.
 */
class PortalPlanControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    private final PlanManagementUseCase plans = mock(PlanManagementUseCase.class);
    private final Authentication authentication = mock(Authentication.class);

    private PortalPlanController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalPlanController(plans, new PlanActionPayloadCodec(new ObjectMapper()));
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    private Plan plan() {
        return Plan.restore(PLAN_ID, UUID.randomUUID(), "VIP Bronze", "Desc", Money.of("39.90"),
                null, "#8E44AD", false, true, NOW, NOW);
    }

    @Test
    void listReturnsPlansWithHasActionsFlag() {
        when(plans.list(MERCHANT_ID)).thenReturn(List.of(new PlanView(plan(), true)));

        List<PlanResponse> response = controller.list(authentication);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).name()).isEqualTo("VIP Bronze");
        assertThat(response.get(0).hasDeliveryActions()).isTrue();
    }

    @Test
    void createReturns201() {
        when(plans.create(any(), any())).thenReturn(plan());

        ResponseEntity<PlanResponse> response = controller.create(authentication,
                new PlanRequest("VIP Bronze", new BigDecimal("39.90"), null, null, "#8E44AD", true));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().price().toString()).isEqualTo("39.90");
    }

    @Test
    void listActionsDecodesPayload() {
        DeliveryAction action = DeliveryAction.restore(UUID.randomUUID(), PLAN_ID, 0,
                DeliveryActionType.SEND_LINK, "{\"url\":\"https://t.me/+abc\",\"label\":\"Grupo VIP\"}", NOW);
        when(plans.listActions(MERCHANT_ID, PLAN_ID)).thenReturn(List.of(action));

        List<PlanActionResponse> response = controller.listActions(authentication, PLAN_ID);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).type()).isEqualTo(DeliveryActionType.SEND_LINK);
        assertThat(response.get(0).payload().get("url")).isEqualTo("https://t.me/+abc");
    }

    @Test
    void addActionReturns201() {
        DeliveryAction action = DeliveryAction.create(PLAN_ID, 0, DeliveryActionType.GRANT_ROLE,
                "{\"roleId\":\"r1\"}", NOW);
        when(plans.addAction(any(), any(), any())).thenReturn(action);

        ResponseEntity<PlanActionResponse> response = controller.addAction(authentication, PLAN_ID,
                new PlanActionRequest(0, "GRANT_ROLE", Map.of("roleId", "r1")));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().payload().get("roleId")).isEqualTo("r1");
    }

    @Test
    void addActionWithInvalidTypeThrows400() {
        assertThatThrownBy(() -> controller.addAction(authentication, PLAN_ID,
                new PlanActionRequest(0, "NAO_EXISTE", Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getUnknownPlanPropagates404() {
        when(plans.get(MERCHANT_ID, PLAN_ID)).thenThrow(new PlanNotFoundException());

        assertThatThrownBy(() -> controller.get(authentication, PLAN_ID))
                .isInstanceOf(PlanNotFoundException.class);
    }

    @Test
    void deleteActionReturns204() {
        ResponseEntity<Void> response = controller.deleteAction(authentication, UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(plans).deleteAction(any(), any());
    }
}
