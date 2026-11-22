package com.foxcash.application.service;

import com.foxcash.domain.exception.BroadcastNotFoundException;
import com.foxcash.domain.exception.InvalidBroadcastStateException;
import com.foxcash.domain.exception.MasterPermissionDeniedException;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.broadcast.Broadcast;
import com.foxcash.domain.model.broadcast.BroadcastMode;
import com.foxcash.domain.model.broadcast.BroadcastStatus;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.storebot.BotPlatform;
import com.foxcash.domain.model.storebot.BotStatus;
import com.foxcash.domain.model.storebot.StoreBot;
import com.foxcash.domain.port.out.BroadcastRepository;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.StoreBotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterBroadcastCommandService} (Spec 89) — force-cancel:
 * sucesso (FAILED + error_message + auditoria na mesma transação), 409 em
 * não-PENDING, 404, 403 sem permissão.
 */
class MasterBroadcastCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");
    private static final UUID OPERATOR_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final BroadcastRepository broadcastRepository = mock(BroadcastRepository.class);
    private final StoreBotRepository botRepository = mock(StoreBotRepository.class);
    private final MasterAuditRecorder auditRecorder = mock(MasterAuditRecorder.class);
    private final MasterRepository masterRepository = mock(MasterRepository.class);
    private final MasterPermissionGuard permissionGuard =
            new MasterPermissionGuard(masterRepository);
    private MasterBroadcastCommandService service;

    @BeforeEach
    void setUp() {
        service = new MasterBroadcastCommandService(broadcastRepository, botRepository,
                auditRecorder, permissionGuard, Clock.fixed(NOW, ZoneOffset.UTC));
        when(masterRepository.findById(OPERATOR_ID))
                .thenReturn(Optional.of(Master.restore(OPERATOR_ID, "op", "hash", "Operador",
                        true, false, NOW)));
    }

    private Broadcast broadcast(UUID id, BroadcastStatus status) {
        return Broadcast.restore(id, UUID.randomUUID(), BroadcastMode.NOW, "chat-1", "conteudo",
                null, null, null, null, status, null, null, null, NOW, NOW);
    }

    @Test
    void forceCancelPendingMarksFailedAndAuditsInSameTransaction() {
        UUID id = UUID.randomUUID();
        Broadcast pending = broadcast(id, BroadcastStatus.PENDING);
        when(broadcastRepository.findById(id)).thenReturn(Optional.of(pending));
        when(broadcastRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(botRepository.findById(any())).thenReturn(Optional.of(StoreBot.restore(
                pending.storeBotId(), MERCHANT_ID, BotPlatform.TELEGRAM, "123:ABC", "h", "wh",
                "Bot", "bot", null, BotStatus.ACTIVE, null, NOW, null, NOW, NOW)));

        Broadcast result = service.forceCancel(id, OPERATOR_ID, "10.0.0.1");

        assertThat(result.status()).isEqualTo(BroadcastStatus.FAILED);
        assertThat(result.errorMessage()).isEqualTo(MasterBroadcastCommandService.FORCE_CANCEL_REASON);

        ArgumentCaptor<MasterAuditAction> action = ArgumentCaptor.forClass(MasterAuditAction.class);
        ArgumentCaptor<UUID> merchantCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(auditRecorder).record(org.mockito.ArgumentMatchers.eq(OPERATOR_ID), action.capture(),
                merchantCaptor.capture(), org.mockito.ArgumentMatchers.eq("BROADCAST"),
                org.mockito.ArgumentMatchers.eq(id), any(), any(),
                org.mockito.ArgumentMatchers.eq(MasterBroadcastCommandService.FORCE_CANCEL_REASON),
                org.mockito.ArgumentMatchers.eq("10.0.0.1"));
        assertThat(action.getValue()).isEqualTo(MasterAuditAction.BROADCAST_FORCE_CANCELLED);
        assertThat(merchantCaptor.getValue()).isEqualTo(MERCHANT_ID);
    }

    @Test
    void forceCancelNonPendingThrows409() {
        UUID id = UUID.randomUUID();
        when(broadcastRepository.findById(id))
                .thenReturn(Optional.of(broadcast(id, BroadcastStatus.PROCESSING)));

        assertThatThrownBy(() -> service.forceCancel(id, OPERATOR_ID, "10.0.0.1"))
                .isInstanceOf(InvalidBroadcastStateException.class);
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void forceCancelUnknownThrows404() {
        UUID id = UUID.randomUUID();
        when(broadcastRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forceCancel(id, OPERATOR_ID, "10.0.0.1"))
                .isInstanceOf(BroadcastNotFoundException.class);
    }

    @Test
    void forceCancelWithoutPermissionThrows403() {
        UUID otherMaster = UUID.randomUUID();
        when(masterRepository.findById(otherMaster)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.forceCancel(UUID.randomUUID(), otherMaster, "10.0.0.1"))
                .isInstanceOf(MasterPermissionDeniedException.class);
    }
}
