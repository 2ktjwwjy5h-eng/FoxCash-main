package com.foxcash.application.service;

import com.foxcash.domain.exception.MasterProfileAccessDeniedException;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.port.out.MasterRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Guard de autorização da gestão de perfis master e do relatório de atividades
 * (Spec 41): apenas o admin ({@code can_manage_masters}) ativo executa →
 * {@code MasterProfileAccessDeniedException} (403 {@code forbidden}).
 *
 * <p><b>Spec 69 (autorização em 2 níveis):</b> este guard é a IMPLEMENTAÇÃO do
 * nível ADMIN para a permissão {@link com.foxcash.domain.model.master.MasterPermission#MASTER_PROFILES_MANAGE}
 * (derivada de {@code can_manage_masters} — tier-based, mesma regra do
 * {@link MasterPermissionGuard}); as escritas de gestão de perfis também
 * exigem step-up MFA (ver {@link MasterProfileService}).
 */
@Service
public class MasterAdminGuard {

    private final MasterRepository masterRepository;

    public MasterAdminGuard(MasterRepository masterRepository) {
        this.masterRepository = masterRepository;
    }

    /** Lança exceção se o master não for admin ativo; devolve o master caso contrário. */
    public Master requireAdmin(UUID actorMasterId) {
        Master actor = masterRepository.findById(actorMasterId)
                .filter(Master::isActive)
                .orElseThrow(MasterProfileAccessDeniedException::new);
        if (!actor.canManageMasters()) {
            throw new MasterProfileAccessDeniedException();
        }
        return actor;
    }
}
