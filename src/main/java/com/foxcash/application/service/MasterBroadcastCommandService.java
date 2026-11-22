package com.foxcash.application.service;

import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.exception.InvalidBroadcastStateException;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicação: intervenção do Master Console nos broadcasts (Spec 89) — o
 * **force-cancel** de campanhas abusivas {@code PENDING} (qualquer modo). O
 * broadcast vai para {@code FAILED} com {@code error_message = 'Cancelled by
 * Master Operator'} — não é delete físico (o registro permanece para
 * auditoria). RBAC nível OPERATOR (Spec 69) + trilha
 * {@code BROADCAST_FORCE_CANCELLED} na MESMA transação (Spec 37).
 */
@Service
public class MasterBroadcastCommandService {

    public static final String FORCE_CANCEL_REASON = "Cancelled by Master Operator";

    private final BroadcastRepository broadcastRepository;
    private final StoreBotRepository botRepository;
    private final MasterAuditRecorder auditRecorder;
    private final MasterPermissionGuard permissionGuard;
    private final Clock clock;

    public MasterBroadcastCommandService(BroadcastRepository broadcastRepository,
                                         StoreBotRepository botRepository,
                                         MasterAuditRecorder auditRecorder,
                                         MasterPermissionGuard permissionGuard,
                                         Clock clock) {
        this.broadcastRepository = broadcastRepository;
        this.botRepository = botRepository;
        this.auditRecorder = auditRecorder;
        this.permissionGuard = permissionGuard;
        this.clock = clock;
    }

    /**
     * Cancela forçadamente um broadcast {@code PENDING}. {@code operatorId} vem
     * do principal (master.id()). Auditoria (Spec 37) na mesma transação com
     * before/after {@code {status}} e reason fixa.
     *
     * @return o broadcast atualizado ({@code status: FAILED})
     */
    @Transactional
    public Broadcast forceCancel(UUID broadcastId, UUID operatorId, String ip) {
        // RBAC (Spec 69): ação de OPERATOR — sem step-up (intervenção reversível,
        // padrão suspend/release). 403 MasterPermissionDeniedException.
        permissionGuard.requirePermission(operatorId, MasterPermission.BROADCAST_FORCE_CANCEL);

        Broadcast broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(BroadcastNotFoundException::new);
        if (broadcast.status() != BroadcastStatus.PENDING) {
            throw new InvalidBroadcastStateException();
        }

        BroadcastStatus before = broadcast.status();
        broadcast.forceCancel(FORCE_CANCEL_REASON, clock.instant());
        Broadcast saved = broadcastRepository.save(broadcast);

        // Auditoria na MESMA transação (Spec 37): actor = operador, reference =
        // BROADCAST/{id}, merchantId do bot dono, before/after status.
        UUID merchantId = botRepository.findById(saved.storeBotId())
                .map(StoreBot::merchantId)
                .orElse(null);
        auditRecorder.record(operatorId, MasterAuditAction.BROADCAST_FORCE_CANCELLED,
                merchantId, "BROADCAST", broadcastId,
                Map.of("status", before.name()), Map.of("status", saved.status().name()),
                FORCE_CANCEL_REASON, ip);
        return saved;
    }
}
