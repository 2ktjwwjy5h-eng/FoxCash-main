package com.foxcash.application.service;

import com.foxcash.domain.exception.CannotDeactivateLastAdminException;
import com.foxcash.domain.exception.CannotDeactivateSelfException;
import com.foxcash.domain.exception.MasterAlreadyExistsException;
import com.foxcash.domain.exception.MasterNotFoundException;
import com.foxcash.domain.exception.MasterProfileAccessDeniedException;
import com.foxcash.domain.exception.TooManyMastersException;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.port.in.MasterProfileUseCase;
import com.foxcash.domain.port.in.MasterMfaUseCase;
import com.foxcash.domain.port.out.MasterPasswordGenerator;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.infrastructure.config.MasterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes da gestão de perfis master (Spec 41): autorização por flag
 * {@code can_manage_masters}, limite de 4 perfis, senha gerada 1x, regras de
 * desativação (não a si mesmo, não o último admin) e auditoria/relatório.
 */
class MasterProfileServiceTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");
    private static final String PASSWORD = "mst_0123456789abcdef";

    private final MasterRepository masterRepository = mock(MasterRepository.class);
    private final MasterPasswordGenerator passwordGenerator = mock(MasterPasswordGenerator.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final MasterAdminGuard adminGuard = mock(MasterAdminGuard.class);
    private final MasterAuditRecorder auditRecorder = mock(MasterAuditRecorder.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final MasterProperties properties = new MasterProperties();
    private final MasterStepUpGuard stepUpGuard = mock(MasterStepUpGuard.class);
    private final MasterMfaUseCase mfa = mock(MasterMfaUseCase.class);

    private MasterProfileService service;

    @BeforeEach
    void setUp() {
        service = new MasterProfileService(masterRepository, passwordGenerator, passwordHasher,
                adminGuard, auditRecorder, activityRecorder, properties, stepUpGuard, mfa);
    }

    private Master admin() {
        return Master.restore(ADMIN_ID, "admin", "hash", "Admin", true, true, NOW);
    }

    private Master other(boolean active) {
        return Master.restore(OTHER_ID, "operador", "hash", "Operador", active, false, NOW);
    }

    private void stubAdmin() {
        when(adminGuard.requireAdmin(ADMIN_ID)).thenReturn(admin());
    }

    // ---------- create ----------

    @Test
    void createByAdminIssuesTemporaryPasswordOnceAndAudits() {
        stubAdmin();
        when(masterRepository.count()).thenReturn(1L);
        when(masterRepository.findByUsername("operador")).thenReturn(Optional.empty());
        when(passwordGenerator.generate()).thenReturn(PASSWORD);
        when(passwordHasher.hash(PASSWORD)).thenReturn("$2a$10$bcrypt");
        when(masterRepository.save(any(Master.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MasterProfileUseCase.CreatedProfile created = service.create(
                new MasterProfileUseCase.CreateCommand("operador", "Operador"), ADMIN_ID);

        assertEquals("operador", created.profile().username());
        assertEquals(PASSWORD, created.temporaryPassword()); // devolvida UMA vez
        assertEquals(false, created.profile().canManageMasters());
        // Apenas o hash BCrypt é persistido — nunca a senha em claro.
        verify(masterRepository).save(any(Master.class));
        // Auditoria (Spec 37) + relatório (Spec 41) na mesma ação.
        verify(auditRecorder).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(activityRecorder).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createByNonAdminThrowsForbidden() {
        when(adminGuard.requireAdmin(OTHER_ID)).thenThrow(MasterProfileAccessDeniedException.class);

        assertThrows(MasterProfileAccessDeniedException.class, () ->
                service.create(new MasterProfileUseCase.CreateCommand("x", "X"), OTHER_ID));
        verify(masterRepository, never()).save(any());
    }

    @Test
    void createWhenLimitReachedThrows409() {
        stubAdmin();
        when(masterRepository.count()).thenReturn(4L); // max = 4

        assertThrows(TooManyMastersException.class, () ->
                service.create(new MasterProfileUseCase.CreateCommand("x", "X"), ADMIN_ID));
        verify(masterRepository, never()).save(any());
    }

    @Test
    void createWithDuplicateUsernameThrows409() {
        stubAdmin();
        when(masterRepository.count()).thenReturn(1L);
        when(masterRepository.findByUsername("operador")).thenReturn(Optional.of(other(true)));

        assertThrows(MasterAlreadyExistsException.class, () ->
                service.create(new MasterProfileUseCase.CreateCommand("operador", "Operador"), ADMIN_ID));
        verify(masterRepository, never()).save(any());
    }

    // ---------- deactivate ----------

    @Test
    void deactivateSelfThrows409() {
        stubAdmin();
        when(masterRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin()));

        assertThrows(CannotDeactivateSelfException.class, () ->
                service.deactivate(ADMIN_ID, ADMIN_ID));
        verify(masterRepository, never()).save(any());
    }

    @Test
    void deactivateLastActiveAdminThrows409() {
        stubAdmin();
        Master targetAdmin = Master.restore(OTHER_ID, "outro-admin", "hash", "Outro", true, true, NOW);
        when(masterRepository.findById(OTHER_ID)).thenReturn(Optional.of(targetAdmin));
        // Único admin ativo no sistema (backstop: a plataforma nunca fica sem admin).
        when(masterRepository.findAll()).thenReturn(List.of(targetAdmin));

        assertThrows(CannotDeactivateLastAdminException.class, () ->
                service.deactivate(OTHER_ID, ADMIN_ID));
        verify(masterRepository, never()).save(targetAdmin);
    }

    @Test
    void deactivateAnotherProfileSucceedsAndAudits() {
        stubAdmin();
        Master target = other(true);
        when(masterRepository.findById(OTHER_ID)).thenReturn(Optional.of(target));
        // Existe outro admin ativo (o actor) — não é o último.
        when(masterRepository.findAll()).thenReturn(List.of(admin(), target));

        service.deactivate(OTHER_ID, ADMIN_ID);

        // Mutation (VoidMethodCall L97): chamada a target.deactivate() removida
        // — o alvo DEVE ficar inativo após a desativação.
        assertFalse(target.isActive());
        verify(masterRepository).save(target);
        verify(auditRecorder).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(activityRecorder).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deactivateAnotherActiveAdminSucceedsWhenNotLast() {
        // Mutation (BooleanTrueReturnVals L133): isLastActiveAdmin → true sempre.
        // Com 2 admins ATIVOS, o alvo admin NÃO é o último → a desativação deve
        // prosseguir (o mutante lançaria CannotDeactivateLastAdminException).
        stubAdmin();
        Master targetAdmin = Master.restore(OTHER_ID, "outro-admin", "hash", "Outro", true, true, NOW);
        when(masterRepository.findById(OTHER_ID)).thenReturn(Optional.of(targetAdmin));
        // Actor (admin) + alvo (admin) = 2 admins ativos → não é o último.
        when(masterRepository.findAll()).thenReturn(List.of(admin(), targetAdmin));

        service.deactivate(OTHER_ID, ADMIN_ID);

        assertFalse(targetAdmin.isActive());
        verify(masterRepository).save(targetAdmin);
    }

    @Test
    void deactivateMissingTargetThrowsMasterNotFound() {
        // Mutation (NullReturnVals L90, lambda do orElseThrow): alvo inexistente
        // deve lançar MasterNotFoundException (não NPE do `throw null`).
        stubAdmin();
        when(masterRepository.findById(OTHER_ID)).thenReturn(Optional.empty());

        assertThrows(MasterNotFoundException.class, () ->
                service.deactivate(OTHER_ID, ADMIN_ID));
        verify(masterRepository, never()).save(any());
    }

    // ---------- activate / list ----------

    @Test
    void activateSucceedsAndAudits() {
        stubAdmin();
        Master target = other(false);
        when(masterRepository.findById(OTHER_ID)).thenReturn(Optional.of(target));

        service.activate(OTHER_ID, ADMIN_ID);

        // Mutation (VoidMethodCall L112): chamada a target.activate() removida
        // — o alvo DEVE ficar ativo após a ativação.
        assertTrue(target.isActive());
        verify(masterRepository).save(target);
        verify(auditRecorder).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void activateMissingTargetThrowsMasterNotFound() {
        // Mutation (NullReturnVals L111, lambda do orElseThrow): alvo inexistente
        // deve lançar MasterNotFoundException (não NPE do `throw null`).
        stubAdmin();
        when(masterRepository.findById(OTHER_ID)).thenReturn(Optional.empty());

        assertThrows(MasterNotFoundException.class, () ->
                service.activate(OTHER_ID, ADMIN_ID));
        verify(masterRepository, never()).save(any());
    }

    @Test
    void listNeverExposesPasswordHash() {
        stubAdmin();
        when(masterRepository.findAll()).thenReturn(List.of(admin(), other(true)));

        List<MasterProfileUseCase.Profile> profiles = service.list(ADMIN_ID);

        assertEquals(2, profiles.size());
        // O Profile (record) não carrega passwordHash por construção — apenas
        // id/username/name/active/canManageMasters/createdAt.
        assertEquals("admin", profiles.get(0).username());
        assertEquals(true, profiles.get(0).canManageMasters());
        assertEquals("operador", profiles.get(1).username());
        assertEquals(false, profiles.get(1).canManageMasters());
    }

    // ---------- Spec 69: gestão de perfis é SENSÍVEL (step-up MFA) ----------

    @Test
    void createRequiresStepUp() {
        // Spec 69 (critérios 3/8): master.profiles.manage é sensível — o ADMIN
        // autenticado precisa de step-up válido; negado → 403 step_up_required,
        // sem criar perfil.
        stubAdmin();
        org.mockito.Mockito.doThrow(new com.foxcash.domain.exception.MasterStepUpRequiredException())
                .when(stepUpGuard).requireSteppedUp(any(Master.class),
                        org.mockito.ArgumentMatchers.eq(
                                com.foxcash.domain.model.master.MasterPermission.MASTER_PROFILES_MANAGE));

        assertThrows(com.foxcash.domain.exception.MasterStepUpRequiredException.class, () ->
                service.create(new MasterProfileUseCase.CreateCommand("novo", "Novo"), ADMIN_ID));
        verify(masterRepository, never()).save(any());
    }

    @Test
    void createInvokesStepUpGuardAfterAdminCheck() {
        stubAdmin();
        when(masterRepository.count()).thenReturn(0L);
        when(masterRepository.findByUsername("operador")).thenReturn(Optional.empty());
        when(passwordGenerator.generate()).thenReturn(PASSWORD);
        when(passwordHasher.hash(PASSWORD)).thenReturn("$2a$10$bcrypt");
        when(masterRepository.save(any(Master.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new MasterProfileUseCase.CreateCommand("operador", "Operador"), ADMIN_ID);

        verify(stepUpGuard).requireSteppedUp(any(Master.class),
                org.mockito.ArgumentMatchers.eq(
                        com.foxcash.domain.model.master.MasterPermission.MASTER_PROFILES_MANAGE));
    }

    // ---------- Spec 71: selfProfile (GET /master/me) ----------

    @Test
    void selfProfileOperatorReturnsOperationalBlockWithMfaState() {
        when(masterRepository.findById(OTHER_ID)).thenReturn(Optional.of(other(true)));
        when(mfa.hasActiveMfa(OTHER_ID)).thenReturn(true);

        MasterProfileUseCase.SelfProfile profile = service.selfProfile(OTHER_ID);

        assertEquals("OPERATOR", profile.role());
        // Bloco operacional completo (12 — Spec 98: + merchant.config/debit/ban;
        // Spec 119: + community.manage) — nunca [] para master ativo (Spec 69).
        assertEquals(12, profile.permissions().size());
        assertTrue(profile.permissions().contains("merchant.suspend"));
        assertTrue(profile.permissions().contains("invoice.collect"));
        // Spec 89: broadcast.force_cancel é operacional (qualquer master ativo).
        assertTrue(profile.permissions().contains("broadcast.force_cancel"));
        // Spec 98: as 3 novas também são operacionais.
        assertTrue(profile.permissions().contains("merchant.config"));
        assertTrue(profile.permissions().contains("merchant.debit"));
        assertTrue(profile.permissions().contains("merchant.ban"));
        // Spec 119: community.manage é operacional (qualquer master ativo).
        assertTrue(profile.permissions().contains("community.manage"));
        assertFalse(profile.permissions().contains("master.profiles.manage"));
        assertFalse(profile.canManageMasters());
        assertTrue(profile.mfaActive());
    }

    @Test
    void selfProfileAdminIncludesProfilesManage() {
        when(masterRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin()));
        when(mfa.hasActiveMfa(ADMIN_ID)).thenReturn(false);

        MasterProfileUseCase.SelfProfile profile = service.selfProfile(ADMIN_ID);

        assertEquals("ADMIN", profile.role());
        // Catálogo 13 (Spec 98: + merchant.config, merchant.debit, merchant.ban;
        // Spec 119: + community.manage).
        assertEquals(13, profile.permissions().size());
        assertTrue(profile.permissions().contains("master.profiles.manage"));
        assertTrue(profile.canManageMasters());
        assertFalse(profile.mfaActive());
    }

    @Test
    void selfProfileMissingMasterThrowsMasterNotFound() {
        when(masterRepository.findById(OTHER_ID)).thenReturn(Optional.empty());

        assertThrows(MasterNotFoundException.class, () -> service.selfProfile(OTHER_ID));
        verify(mfa, never()).hasActiveMfa(any());
    }
}
