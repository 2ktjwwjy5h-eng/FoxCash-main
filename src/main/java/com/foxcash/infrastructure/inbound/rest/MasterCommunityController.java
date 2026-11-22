package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.CommunityService;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.community.CommunityPost;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import com.foxcash.infrastructure.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Adaptador REST: gestão de posts da Comunidade no Master Console (Spec 119).
 * Role {@code ROLE_MASTER} (principal = master.id()), RBAC nível OPERATOR
 * (Spec 69 — {@code COMMUNITY_MANAGE}) e auditoria {@code COMMUNITY_POST_*}
 * na mesma transação (Spec 37). KISS: sem chat/comentários.
 */
@RestController
@RequestMapping("/api/v1/master/community/posts")
public class MasterCommunityController {

    private final CommunityService communityService;
    private final ClientIpResolver clientIpResolver;

    public MasterCommunityController(CommunityService communityService,
                                     ClientIpResolver clientIpResolver) {
        this.communityService = communityService;
        this.clientIpResolver = clientIpResolver;
    }

    /** Listagem paginada (console — RBAC de leitura: qualquer master ativo). */
    @GetMapping
    public PageResponse<MasterCommunityPostResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<CommunityPost> result = communityService.listMaster(
                QueryParamParser.clampPage(page), QueryParamParser.clampSize(size));
        return PageResponse.from(result, MasterCommunityPostResponse::from);
    }

    /** Cria um post (OPERATOR + auditoria). */
    @PostMapping
    public MasterCommunityPostResponse create(Authentication authentication,
                                              HttpServletRequest httpRequest,
                                              @RequestBody CommunityPostRequest request) {
        UUID operatorId = (UUID) authentication.getPrincipal();
        String ip = clientIpResolver.resolve(httpRequest);
        CommunityPost post = communityService.create(operatorId, request.title(), request.content(),
                request.pinned() != null && request.pinned(), ip);
        return MasterCommunityPostResponse.from(post);
    }

    /** Edita título/conteúdo/pinned (OPERATOR + auditoria). */
    @PatchMapping("/{id}")
    public MasterCommunityPostResponse update(Authentication authentication,
                                              HttpServletRequest httpRequest,
                                              @PathVariable UUID id,
                                              @RequestBody CommunityPostUpdateRequest request) {
        UUID operatorId = (UUID) authentication.getPrincipal();
        String ip = clientIpResolver.resolve(httpRequest);
        CommunityPost post = communityService.update(operatorId, id,
                request.title(), request.content(), request.pinned(), ip);
        return MasterCommunityPostResponse.from(post);
    }

    /** Remove um post (OPERATOR + auditoria). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication,
                                       HttpServletRequest httpRequest,
                                       @PathVariable UUID id) {
        UUID operatorId = (UUID) authentication.getPrincipal();
        String ip = clientIpResolver.resolve(httpRequest);
        communityService.delete(operatorId, id, ip);
        return ResponseEntity.noContent().build();
    }

    /** Body do POST — título/conteúdo obrigatórios; pinned opcional (default false). */
    public record CommunityPostRequest(String title, String content, Boolean pinned) {
    }

    /** Body do PATCH — campos opcionais (null = não alterar). */
    public record CommunityPostUpdateRequest(String title, String content, Boolean pinned) {
    }

    /** Response do post no console (inclui o autor para a UI). */
    public record MasterCommunityPostResponse(
            UUID id,
            String title,
            String content,
            UUID publishedBy,
            boolean pinned,
            Instant publishedAt) {

        static MasterCommunityPostResponse from(CommunityPost post) {
            return new MasterCommunityPostResponse(post.id(), post.title(), post.content(),
                    post.publishedBy(), post.pinned(), post.publishedAt());
        }
    }
}
