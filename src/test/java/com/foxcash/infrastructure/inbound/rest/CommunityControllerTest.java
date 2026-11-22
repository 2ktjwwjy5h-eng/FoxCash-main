package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.CommunityService;
import com.foxcash.domain.model.community.CommunityPost;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes dos controllers da Comunidade (Spec 119): portal read-only (paginado
 * com cap 50) e master CRUD (RBAC + auditoria delegados ao service).
 */
class CommunityControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID MASTER_ID = UUID.randomUUID();

    private final CommunityService communityService = mock(CommunityService.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final Authentication authentication = mock(Authentication.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private PortalCommunityController portalController;
    private MasterCommunityController masterController;

    @BeforeEach
    void setUp() {
        portalController = new PortalCommunityController(communityService);
        masterController = new MasterCommunityController(communityService, clientIpResolver);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    @Test
    void portalListDelegatesToServiceWithMerchantScope() {
        when(communityService.list(0, 20)).thenReturn(
                com.foxcash.domain.model.PageResult.of(java.util.List.of(), 0, 20, 0));

        PageResponse<PortalCommunityController.CommunityPostResponse> response =
                portalController.list(authentication, 0, 20);

        assertThat(response.items()).isEmpty();
        verify(communityService).list(0, 20);
    }

    @Test
    void portalListCapsSizeAtFifty() {
        when(communityService.list(0, 50)).thenReturn(
                com.foxcash.domain.model.PageResult.of(java.util.List.of(), 0, 50, 0));

        portalController.list(authentication, 0, 500);

        verify(communityService).list(0, 50);
    }

    @Test
    void masterCreateDelegatesWithOperatorIdAndIp() {
        when(authentication.getPrincipal()).thenReturn(MASTER_ID);
        when(clientIpResolver.resolve(request)).thenReturn("10.0.0.1");
        CommunityPost post = CommunityPost.create("Titulo", "Conteudo", MASTER_ID, false,
                Instant.parse("2026-08-17T12:00:00Z"));
        when(communityService.create(MASTER_ID, "Titulo", "Conteudo", false, "10.0.0.1"))
                .thenReturn(post);

        MasterCommunityController.MasterCommunityPostResponse response =
                masterController.create(authentication, request,
                        new MasterCommunityController.CommunityPostRequest("Titulo", "Conteudo", false));

        assertThat(response.title()).isEqualTo("Titulo");
        assertThat(response.publishedBy()).isEqualTo(MASTER_ID);
    }

    @Test
    void masterUpdateDelegatesWithIp() {
        when(authentication.getPrincipal()).thenReturn(MASTER_ID);
        when(clientIpResolver.resolve(request)).thenReturn("10.0.0.1");
        UUID postId = UUID.randomUUID();
        CommunityPost post = CommunityPost.create("Novo", "Conteudo", MASTER_ID, true,
                Instant.parse("2026-08-17T12:00:00Z"));
        when(communityService.update(MASTER_ID, postId, "Novo", "Conteudo", true, "10.0.0.1"))
                .thenReturn(post);

        MasterCommunityController.MasterCommunityPostResponse response =
                masterController.update(authentication, request, postId,
                        new MasterCommunityController.CommunityPostUpdateRequest("Novo", "Conteudo", true));

        assertThat(response.title()).isEqualTo("Novo");
        assertThat(response.pinned()).isTrue();
    }
}
