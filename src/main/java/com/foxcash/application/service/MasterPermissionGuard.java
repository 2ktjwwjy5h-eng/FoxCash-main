package com.foxcash.application.service;

import com.foxcash.domain.exception.MasterPermissionDeniedException;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterLevel;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.port.out.MasterRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Guard de autorizacao por PERMISSAO da acao administrativa do master (Spec 69,
 * F1) — camada do meio da cadeia {@code authenticated → ROLE_MASTER → permissão
 * → step-up (se sensível) → executa → audit log}.
 *
 * <p><b>Tier-based (decisao do usuario 2026-08-11):</b> a concessao e por
 * NIVEL, derivada de {@code can_manage_masters} ({@link MasterLevel}):
 * as 8 permissoes operacionais do catalogo → qualquer master ATIVO tem;
 * {@link MasterPermission#MASTER_PROFILES_MANAGE} → apenas
 * {@code can_manage_masters=true} (ADMIN). Negado → {@link
 * MasterPermissionDeniedException} (403 {@code forbidden}).
 *
 * <p>Chamado DENTRO dos services (mesma transacao) — protege HTTP + Operator
 * Bot + futuro Master Console com o mesmo guard. O {@code actorId} vem do
 * {@code MasterAuthFilter} (principal = master.id()).
 */
@Service
public class MasterPermissionGuard {

    private final MasterRepository masterRepository;

    public MasterPermissionGuard(MasterRepository masterRepository) {
        this.masterRepository = masterRepository;
    }

    /**
     * Exige a permissao da acao; devolve o master ativo (para o service usar o
     * actor carregado) ou lança {@link MasterPermissionDeniedException}.
     */
    public Master requirePermission(UUID actorMasterId, MasterPermission permission) {
        Master actor = masterRepository.findById(actorMasterId)
                .filter(Master::isActive)
                .orElseThrow(MasterPermissionDeniedException::new);
        if (permission == MasterPermission.MASTER_PROFILES_MANAGE && !actor.canManageMasters()) {
            throw new MasterPermissionDeniedException();
        }
        return actor;
    }

    /** Variante por valor canônico ({@code "merchant.suspend"}) — valida no catálogo. */
    public Master requirePermission(UUID actorMasterId, String permission) {
        return requirePermission(actorMasterId, MasterPermission.fromValue(permission));
    }
}
