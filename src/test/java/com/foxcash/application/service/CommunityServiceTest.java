package com.foxcash.application.service;

import com.foxcash.domain.exception.CommunityPostNotFoundException;
import com.foxcash.domain.exception.MasterPermissionDeniedException;
import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.community.CommunityPost;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.port.out.CommunityPostRepository;
import com.foxcash.domain.port.out.MasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link CommunityService} (Spec 119): CRUD do master com RBAC
 * (OPERATOR) + auditoria e leitura paginada do portal (pinados primeiro).
 */
class CommunityServiceTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final String IP = "127.0.0.1";

    private final CommunityPostRepository postRepository = mock(CommunityPostRepository.class);
    private final MasterRepository masterRepository = mock(MasterRepository.class);
    private final MasterAuditRecorder auditRecorder = mock(MasterAuditRecorder.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-17T12:00:00Z"), ZoneOffset.UTC);

    private CommunityService service;

    @BeforeEach
    void setUp() {
        service = new CommunityService(postRepository,
                new MasterPermissionGuard(masterRepository), auditRecorder, clock);
    }

    private Master activeMaster() {
        return Master.restore(MASTER_ID, "operator", "hash", "Operador", true, false,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void createRequiresActiveMasterAndSaves() {
        when(masterRepository.findById(MASTER_ID)).thenReturn(Optional.of(activeMaster()));
        CommunityPost saved = CommunityPost.create("Titulo", "Conteudo", MASTER_ID, true, clock.instant());
        when(postRepository.save(any())).thenReturn(saved);

        CommunityPost result = service.create(MASTER_ID, "Titulo", "Conteudo", true, IP);

        assertThat(result.title()).isEqualTo("Titulo");
        assertThat(result.pinned()).isTrue();
        verify(postRepository).save(any());
        verify(auditRecorder).record(org.mockito.ArgumentMatchers.eq(MASTER_ID),
                org.mockito.ArgumentMatchers.eq(com.foxcash.domain.model.audit.MasterAuditAction.COMMUNITY_POST_CREATED),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("COMMUNITY_POST"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(IP));
    }

    @Test
    void createDeniesInactiveMaster() {
        when(masterRepository.findById(MASTER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(MASTER_ID, "Titulo", "Conteudo", false, IP))
                .isInstanceOf(MasterPermissionDeniedException.class);
    }

    @Test
    void updateEditsFieldsAndAudits() {
        when(masterRepository.findById(MASTER_ID)).thenReturn(Optional.of(activeMaster()));
        CommunityPost existing = CommunityPost.create("Velho", "Conteudo antigo", MASTER_ID, false, clock.instant());
        when(postRepository.findById(existing.id())).thenReturn(Optional.of(existing));
        CommunityPost updated = existing.update("Novo", "Conteudo novo", true, clock.instant());
        when(postRepository.save(any())).thenReturn(updated);

        CommunityPost result = service.update(MASTER_ID, existing.id(), "Novo", "Conteudo novo", true, IP);

        assertThat(result.title()).isEqualTo("Novo");
        assertThat(result.pinned()).isTrue();
        verify(auditRecorder).record(org.mockito.ArgumentMatchers.eq(MASTER_ID),
                org.mockito.ArgumentMatchers.eq(com.foxcash.domain.model.audit.MasterAuditAction.COMMUNITY_POST_UPDATED),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("COMMUNITY_POST"), org.mockito.ArgumentMatchers.eq(existing.id()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(IP));
    }

    @Test
    void updateThrowsNotFoundForUnknownPost() {
        when(masterRepository.findById(MASTER_ID)).thenReturn(Optional.of(activeMaster()));
        when(postRepository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(MASTER_ID, UUID.randomUUID(), "X", "Y", false, IP))
                .isInstanceOf(CommunityPostNotFoundException.class);
    }

    @Test
    void deleteRemovesAndAudits() {
        when(masterRepository.findById(MASTER_ID)).thenReturn(Optional.of(activeMaster()));
        CommunityPost existing = CommunityPost.create("Para remover", "conteudo", MASTER_ID, false, clock.instant());
        when(postRepository.findById(existing.id())).thenReturn(Optional.of(existing));

        service.delete(MASTER_ID, existing.id(), IP);

        verify(postRepository).delete(existing.id());
        verify(auditRecorder).record(org.mockito.ArgumentMatchers.eq(MASTER_ID),
                org.mockito.ArgumentMatchers.eq(com.foxcash.domain.model.audit.MasterAuditAction.COMMUNITY_POST_DELETED),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("COMMUNITY_POST"), org.mockito.ArgumentMatchers.eq(existing.id()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(IP));
    }

    @Test
    void listCapsPortalPageSizeAtFifty() {
        when(postRepository.findAll(0, 50)).thenReturn(PageResult.of(List.of(), 0, 50, 0));

        PageResult<CommunityPost> result = service.list(0, 999);

        assertThat(result.size()).isEqualTo(50);
        verify(postRepository).findAll(0, 50);
    }
}
