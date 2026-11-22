package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.port.in.MasterProfileUseCase;

import java.util.List;
import java.util.UUID;

/**
 * Response do {@code GET /api/v1/master/me} (Spec 71, fatia 1) — perfil do
 * master autenticado com as permissoes resolvidas por nivel (Spec 69) e o estado
 * do MFA. Espelho do {@code /portal/me} do lojista para o console.
 *
 * <p><b>Nunca</b> contem {@code passwordHash} nem PII extra alem do perfil
 * (username/name). {@code permissions[]} nunca e vazio para master ativo
 * (OPERATOR = bloco operacional completo; ADMIN = bloco +
 * {@code master.profiles.manage}).
 */
public record MasterMeResponse(
        UUID masterId,
        String username,
        String name,
        String role,
        List<String> permissions,
        boolean canManageMasters,
        boolean mfaActive) {

    public static MasterMeResponse from(MasterProfileUseCase.SelfProfile profile) {
        return new MasterMeResponse(
                profile.masterId(),
                profile.username(),
                profile.name(),
                profile.role(),
                profile.permissions(),
                profile.canManageMasters(),
                profile.mfaActive());
    }
}
