package com.foxcash.application.service;

import com.foxcash.domain.exception.CommunityPostNotFoundException;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.community.CommunityPost;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.port.out.CommunityPostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: Comunidade (Spec 119). Leitura paginada para o portal (read-only,
 * pinados primeiro, cap 50) e CRUD para o Master Console — ROLE_MASTER com RBAC
 * (Spec 69 — nível OPERATOR) e auditoria {@code COMMUNITY_POST_*} na mesma
 * transação (Spec 37). KISS: sem chat/comentários (fora de escopo).
 */
@Service
public class CommunityService {

    /** Teto do portal (Spec 119 critério 3 — cap 50 por página). */
    public static final int PORTAL_PAGE_SIZE_CAP = 50;

    private final CommunityPostRepository postRepository;
    private final MasterPermissionGuard permissionGuard;
    private final MasterAuditRecorder auditRecorder;
    private final Clock clock;

    public CommunityService(CommunityPostRepository postRepository,
                            MasterPermissionGuard permissionGuard,
                            MasterAuditRecorder auditRecorder,
                            Clock clock) {
        this.postRepository = postRepository;
        this.permissionGuard = permissionGuard;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    // ---- Leitura (portal — ROLE_MERCHANT_PORTAL) ----

    /** Lista paginada de posts (pinados primeiro, depois published_at desc). */
    @Transactional(readOnly = true)
    public PageResult<CommunityPost> list(int page, int size) {
        int capped = Math.min(Math.max(size, 1), PORTAL_PAGE_SIZE_CAP);
        return postRepository.findAll(Math.max(page, 0), capped);
    }

    // ---- CRUD (Master Console — ROLE_MASTER, RBAC OPERATOR) ----

    /** Listagem no console (sem cap — paginação própria do console). */
    @Transactional(readOnly = true)
    public PageResult<CommunityPost> listMaster(int page, int size) {
        return postRepository.findAll(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    /** Cria um post (auditoria COMMUNITY_POST_CREATED na mesma transação). */
    @Transactional
    public CommunityPost create(UUID actorMasterId, String title, String content,
                                boolean pinned, String ip) {
        permissionGuard.requirePermission(actorMasterId, MasterPermission.COMMUNITY_MANAGE);
        CommunityPost post = CommunityPost.create(title, content, actorMasterId, pinned, clock.instant());
        CommunityPost saved = postRepository.save(post);
        auditRecorder.record(actorMasterId, MasterAuditAction.COMMUNITY_POST_CREATED, null,
                "COMMUNITY_POST", saved.id(),
                null, Map.of("title", saved.title(), "pinned", saved.pinned()), null, ip);
        return saved;
    }

    /** Edita título/conteúdo/pinned (auditoria COMMUNITY_POST_UPDATED). */
    @Transactional
    public CommunityPost update(UUID actorMasterId, UUID postId, String title, String content,
                                Boolean pinned, String ip) {
        permissionGuard.requirePermission(actorMasterId, MasterPermission.COMMUNITY_MANAGE);
        CommunityPost existing = postRepository.findById(postId)
                .orElseThrow(CommunityPostNotFoundException::new);
        CommunityPost updated = existing.update(title, content, pinned, clock.instant());
        CommunityPost saved = postRepository.save(updated);
        auditRecorder.record(actorMasterId, MasterAuditAction.COMMUNITY_POST_UPDATED, null,
                "COMMUNITY_POST", postId,
                Map.of("title", existing.title(), "pinned", existing.pinned()),
                Map.of("title", saved.title(), "pinned", saved.pinned()), null, ip);
        return saved;
    }

    /** Remove um post (auditoria COMMUNITY_POST_DELETED). */
    @Transactional
    public void delete(UUID actorMasterId, UUID postId, String ip) {
        permissionGuard.requirePermission(actorMasterId, MasterPermission.COMMUNITY_MANAGE);
        CommunityPost existing = postRepository.findById(postId)
                .orElseThrow(CommunityPostNotFoundException::new);
        postRepository.delete(postId);
        auditRecorder.record(actorMasterId, MasterAuditAction.COMMUNITY_POST_DELETED, null,
                "COMMUNITY_POST", postId,
                Map.of("title", existing.title()), null, null, ip);
    }
}
