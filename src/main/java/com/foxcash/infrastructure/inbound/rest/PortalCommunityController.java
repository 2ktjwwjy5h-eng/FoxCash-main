package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.CommunityService;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.community.CommunityPost;
import com.foxcash.infrastructure.inbound.rest.dto.PageResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Adaptador REST: Comunidade do portal (Spec 119) — lista read-only de posts
 * (avisos/novidades da plataforma), paginada com cap 50. Role
 * {@code ROLE_MERCHANT_PORTAL}. Sem chat/comentários (KISS — fora de escopo).
 */
@RestController
@RequestMapping("/api/v1/portal/community")
public class PortalCommunityController {

    private final CommunityService communityService;

    public PortalCommunityController(CommunityService communityService) {
        this.communityService = communityService;
    }

    /** Lista paginada de posts (pinados primeiro, depois published_at desc). */
    @GetMapping
    public PageResponse<CommunityPostResponse> list(Authentication authentication,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        PageResult<CommunityPost> result = communityService.list(
                QueryParamParser.clampPage(page),
                Math.min(QueryParamParser.clampSize(size), CommunityService.PORTAL_PAGE_SIZE_CAP));
        return PageResponse.from(result, CommunityPostResponse::from);
    }

    /** Post da comunidade na listagem (Sem o conteúdo completo no card? Não — KISS: lista completa). */
    public record CommunityPostResponse(
            UUID id,
            String title,
            String content,
            boolean pinned,
            Instant publishedAt) {

        static CommunityPostResponse from(CommunityPost post) {
            return new CommunityPostResponse(post.id(), post.title(), post.content(),
                    post.pinned(), post.publishedAt());
        }
    }
}
