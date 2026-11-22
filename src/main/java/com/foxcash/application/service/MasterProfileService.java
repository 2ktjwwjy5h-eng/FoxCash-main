package com.foxcash.application.service;

import com.foxcash.domain.exception.CannotDeactivateLastAdminException;
import com.foxcash.domain.exception.CannotDeactivateSelfException;
import com.foxcash.domain.exception.MasterAlreadyExistsException;
import com.foxcash.domain.exception.TooManyMastersException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterLevel;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.port.in.MasterMfaUseCase;
import com.foxcash.domain.port.in.MasterProfileUseCase;
import com.foxcash.domain.port.out.MasterPasswordGenerator;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.infrastructure.config.MasterProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: gestão de perfis master (Spec 41 — autorizada e oculta).
 *
 * <p>Apenas o admin ({@code can_manage_masters}) executa; limite de 4 perfis
 * ({@code foxcash.master.max-profiles}); senha gerada pelo sistema (devolvida
 * UMA vez, apenas o hash BCrypt persistido); não desativa a si mesmo nem o
 * último admin; cada ação gera trilha no {@code master_audit_log} (Spec 37) e
 * evento no {@code platform_activity_log} (Spec 41) — mesma transação.
 *
 * <p>Autorizacao (Spec 69): a permissao {@code master.profiles.manage} e o
 * {@link MasterAdminGuard} (nível ADMIN) — com **step-up MFA** (acao sensivel)
 * nas escritas.
 */
@Service
public class MasterProfileService implements MasterProfileUseCase {

    private final MasterRepository masterRepository;
    private final MasterPasswordGenerator passwordGenerator;
    private final PasswordHasher passwordHasher;
    private final MasterAdminGuard adminGuard;
    private final MasterAuditRecorder auditRecorder;
    private final PlatformActivityRecorder activityRecorder;
    private final MasterProperties properties;
    private final MasterStepUpGuard stepUpGuard;
    private final MasterMfaUseCase mfa;

    public MasterProfileService(MasterRepository masterRepository,
                                MasterPasswordGenerator passwordGenerator,
                                PasswordHasher passwordHasher,
                                MasterAdminGuard adminGuard,
                                MasterAuditRecorder auditRecorder,
                                PlatformActivityRecorder activityRecorder,
                                MasterProperties properties,
                                MasterStepUpGuard stepUpGuard,
                                MasterMfaUseCase mfa) {
        this.masterRepository = masterRepository;
        this.passwordGenerator = passwordGenerator;
        this.passwordHasher = passwordHasher;
        this.adminGuard = adminGuard;
        this.auditRecorder = auditRecorder;
        this.activityRecorder = activityRecorder;
        this.properties = properties;
        this.stepUpGuard = stepUpGuard;
        this.mfa = mfa;
    }

    @Override
    @Transactional
    public CreatedProfile create(CreateCommand command, UUID actorMasterId) {
        Master actor = adminGuard.requireAdmin(actorMasterId);
        // Spec 69: gestao de perfis e acao SENSIVEL — step-up MFA obrigatorio.
        stepUpGuard.requireSteppedUp(actor, MasterPermission.MASTER_PROFILES_MANAGE);
        if (masterRepository.count() >= properties.getMaxProfiles()) {
            throw new TooManyMastersException(properties.getMaxProfiles());
        }
        if (masterRepository.findByUsername(command.username().trim()).isPresent()) {
            throw new MasterAlreadyExistsException();
        }

        String temporaryPassword = passwordGenerator.generate();
        Master created = masterRepository.save(Master.create(
                command.username().trim(),
                passwordHasher.hash(temporaryPassword),
                command.name().trim()));

        auditRecorder.record(actor.id(), MasterAuditAction.MASTER_CREATED, null, "MASTER",
                created.id(), null, Map.of("username", created.username(), "active", created.isActive(),
                        "canManageMasters", created.canManageMasters()), null, null);
        activityRecorder.record(PlatformActorType.MASTER, actor.id(), PlatformAction.MASTER_CREATED,
                "MASTER", created.id(), null, Map.of("username", created.username()));

        return new CreatedProfile(toProfile(created), temporaryPassword);
    }

    @Override
    @Transactional
    public void deactivate(UUID targetId, UUID actorMasterId) {
        Master actor = adminGuard.requireAdmin(actorMasterId);
        // Spec 69: gestao de perfis e acao SENSIVEL — step-up MFA obrigatorio.
        stepUpGuard.requireSteppedUp(actor, MasterPermission.MASTER_PROFILES_MANAGE);
        Master target = masterRepository.findById(targetId)
                .orElseThrow(() -> new com.foxcash.domain.exception.MasterNotFoundException(targetId));
        if (target.id().equals(actor.id())) {
            throw new CannotDeactivateSelfException();
        }
        if (target.canManageMasters() && isLastActiveAdmin(targetId)) {
            throw new CannotDeactivateLastAdminException();
        }
        target.deactivate();
        masterRepository.save(target);

        auditRecorder.record(actor.id(), MasterAuditAction.MASTER_DEACTIVATED, null, "MASTER",
                target.id(), Map.of("active", true), Map.of("active", false), null, null);
        activityRecorder.record(PlatformActorType.MASTER, actor.id(), PlatformAction.MASTER_DEACTIVATED,
                "MASTER", target.id(), null, Map.of("username", target.username()));
    }

    @Override
    @Transactional
    public void activate(UUID targetId, UUID actorMasterId) {
        Master actor = adminGuard.requireAdmin(actorMasterId);
        // Spec 69: gestao de perfis e acao SENSIVEL — step-up MFA obrigatorio.
        stepUpGuard.requireSteppedUp(actor, MasterPermission.MASTER_PROFILES_MANAGE);
        Master target = masterRepository.findById(targetId)
                .orElseThrow(() -> new com.foxcash.domain.exception.MasterNotFoundException(targetId));
        target.activate();
        masterRepository.save(target);

        auditRecorder.record(actor.id(), MasterAuditAction.MASTER_ACTIVATED, null, "MASTER",
                target.id(), Map.of("active", false), Map.of("active", true), null, null);
        activityRecorder.record(PlatformActorType.MASTER, actor.id(), PlatformAction.MASTER_ACTIVATED,
                "MASTER", target.id(), null, Map.of("username", target.username()));
    }

    @Override
    public List<Profile> list(UUID actorMasterId) {
        adminGuard.requireAdmin(actorMasterId);
        return masterRepository.findAll().stream().map(this::toProfile).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SelfProfile selfProfile(UUID masterId) {
        Master master = masterRepository.findById(masterId)
                .orElseThrow(() -> new com.foxcash.domain.exception.MasterNotFoundException(masterId));
        // Spec 69 (decisao 2026-08-11): concessao por NIVEL — todo master ativo
        // tem o bloco operacional completo (7); apenas o ADMIN
        // (can_manage_masters) soma master.profiles.manage. Nunca [] p/ ativo.
        List<String> permissions = new ArrayList<>(Arrays.stream(MasterPermission.values())
                .filter(p -> p != MasterPermission.MASTER_PROFILES_MANAGE)
                .map(MasterPermission::value)
                .toList());
        if (master.canManageMasters()) {
            permissions.add(MasterPermission.MASTER_PROFILES_MANAGE.value());
        }
        return new SelfProfile(master.id(), master.username(), master.name(),
                MasterLevel.of(master).name(), permissions, master.canManageMasters(),
                mfa.hasActiveMfa(masterId));
    }

    /** Último admin ativo? (nunca deixar a plataforma sem admin — Spec 41). */
    private boolean isLastActiveAdmin(UUID targetId) {
        long activeAdmins = masterRepository.findAll().stream()
                .filter(Master::isActive)
                .filter(Master::canManageMasters)
                .count();
        return activeAdmins <= 1;
    }

    private Profile toProfile(Master master) {
        return new Profile(master.id(), master.username(), master.name(), master.isActive(),
                master.canManageMasters(), master.createdAt());
    }
}
