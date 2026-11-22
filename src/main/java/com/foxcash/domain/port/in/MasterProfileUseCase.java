package com.foxcash.domain.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: gestão de perfis master (Spec 41 — autorizada e oculta).
 *
 * <p>Apenas o admin ({@code can_manage_masters}) executa; limite de
 * {@code foxcash.master.max-profiles} (default 4); senha gerada pelo sistema
 * (devolvida UMA vez); cada ação vai para o {@code master_audit_log} (Spec 37).
 */
public interface MasterProfileUseCase {

    CreatedProfile create(CreateCommand command, UUID actorMasterId);

    void deactivate(UUID targetId, UUID actorMasterId);

    void activate(UUID targetId, UUID actorMasterId);

    List<Profile> list(UUID actorMasterId);

    /**
     * @param username username único do novo perfil (login do master)
     * @param name     nome exibido
     */
    record CreateCommand(String username, String name) {
    }

    /**
     * @param profile           perfil criado (sem hash)
     * @param temporaryPassword senha gerada pelo sistema — devolvida UMA vez
     */
    record CreatedProfile(Profile profile, String temporaryPassword) {
    }

    /** Perfil sem o {@code password_hash} (nunca exposto). */
    record Profile(UUID id, String username, String name, boolean active,
                   boolean canManageMasters, Instant createdAt) {
    }

    /**
     * Perfil do master AUTENTICADO para o {@code GET /master/me} (Spec 71) —
     * rota de leitura (sem guard de permissao: todo master ativo se ve). As
     * permissoes vêm do catalogo da Spec 69 resolvido por nivel
     * ({@code can_manage_masters}): OPERATOR → bloco operacional completo (7);
     * ADMIN → bloco + {@code master.profiles.manage} — nunca {@code []} para
     * master ativo. {@code mfaActive} reflete o metodo TOTP ACTIVE (Spec 44).
     */
    record SelfProfile(UUID masterId, String username, String name, String role,
                       List<String> permissions, boolean canManageMasters, boolean mfaActive) {
    }

    /** Perfil do master autenticado (Spec 71 — rota de leitura, sem guard). */
    SelfProfile selfProfile(UUID masterId);
}
