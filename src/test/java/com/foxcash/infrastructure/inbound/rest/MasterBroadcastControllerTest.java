package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.MasterBroadcastCommandService;
import com.foxcash.application.service.MasterBroadcastQueryService;
import com.foxcash.domain.exception.MasterPermissionDeniedException;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.infrastructure.inbound.rest.dto.MasterBroadcastSummary;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterBroadcastController} (Spec 89) — roteamento e RBAC:
 * leitura para qualquer master ativo, force-cancel como ação de OPERATOR.
 */
class MasterBroadcastControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final UUID OPERATOR_ID = UUID.randomUUID();

    private final MasterBroadcastQueryService queryService = mock(MasterBroadcastQueryService.class);
    private final MasterBroadcastCommandService commandService = mock(MasterBroadcastCommandService.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private MasterBroadcastController controller;

    @BeforeEach
    void setUp() {
        controller = new MasterBroadcastController(queryService, commandService, clientIpResolver);
        when(clientIpResolver.resolve(any())).thenReturn("10.0.0.1");
    }

    private Broadcast broadcast() {
        return Broadcast.restore(UUID.randomUUID(), UUID.randomUUID(), BroadcastMode.NOW, "chat-1",
                "conteudo", null, null, null, null, BroadcastStatus.PENDING, null, null, null, NOW, NOW);
    }

    @Test
    void listReturnsPageOfSummaries() {
        Broadcast b = broadcast();
        when(queryService.list(any())).thenReturn(PageResult.of(
                List.of(new MasterBroadcastQueryService.MasterBroadcastView(b, null, null)),
                0, 20, 1));

        PageResponse<MasterBroadcastSummary> response = controller.list(
                null, null, null, null, null, null, 0, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).id()).isEqualTo(b.id());
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void forceCancelReturnsUpdatedDetail() {
        Broadcast updated = broadcast();
        when(commandService.forceCancel(any(), any(), any())).thenReturn(updated);
        when(queryService.detail(any()))
                .thenReturn(new MasterBroadcastQueryService.MasterBroadcastView(updated, null, null));
        when(queryService.logs(any())).thenReturn(List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(OPERATOR_ID);

        var response = controller.forceCancel(updated.id(), auth, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().id()).isEqualTo(updated.id());
    }

    @Test
    void forceCancelPropagatesPermissionDenied() {
        // RBAC (Spec 69/D3): sem permissão de OPERATOR → MasterPermissionDeniedException
        // (403 forbidden via GlobalExceptionHandler). O guard fica no service — o
        // controller só repassa o principal e a exceção propaga.
        Broadcast b = broadcast();
        when(commandService.forceCancel(any(), any(), any()))
                .thenThrow(new MasterPermissionDeniedException());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(OPERATOR_ID);

        org.junit.jupiter.api.Assertions.assertThrows(MasterPermissionDeniedException.class,
                () -> controller.forceCancel(b.id(), auth, null));
    }
}
