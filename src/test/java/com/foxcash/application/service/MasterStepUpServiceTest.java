package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.exception.MasterStepUpRequiredException;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.master.MasterSession;
import com.foxcash.domain.port.out.MasterSessionRepository;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import com.foxcash.infrastructure.security.ClientIpResolver;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterStepUpService} (Spec 69, F3 — critérios 6/7): valida o
 * 2º fator via {@code verifySecondFactor} (reuso), grava {@code stepped_up_at}
 * na sessão e audita {@code MASTER_STEP_UP}; falha de verificação NÃO grava
 * step-up; sessão ausente/de outro master → 403 fail-closed.
 */
class MasterStepUpServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    private final MasterMfaService mfaService = mock(MasterMfaService.class);
    private final MasterSessionRepository sessionRepository = mock(MasterSessionRepository.class);
    private final SessionTokenGenerator tokenGenerator = mock(SessionTokenGenerator.class);
    private final MasterAuditRecorder auditRecorder = mock(MasterAuditRecorder.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final Clock clock = Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    private MasterStepUpService service() {
        return new MasterStepUpService(mfaService, sessionRepository, tokenGenerator,
                auditRecorder, clientIpResolver, clock);
    }

    private MasterSession activeSession(UUID masterId) {
        return MasterSession.restore(SESSION_ID, masterId, "hash",
                NOW.minusSeconds(60), NOW.plusSeconds(3600), null, null);
    }

    @Test
    void stepUpVerifiesFactorMarksSessionAndAudits() {
        when(tokenGenerator.hash("mst_token")).thenReturn("hash");
        when(sessionRepository.findByTokenHash("hash")).thenReturn(Optional.of(activeSession(MASTER_ID)));

        service().stepUp(MASTER_ID, "123456", "Bearer mst_token");

        verify(mfaService).verifySecondFactor(MASTER_ID, "123456");
        verify(sessionRepository).markSteppedUp(SESSION_ID, NOW);
        verify(auditRecorder).record(eq(MASTER_ID), eq(MasterAuditAction.MASTER_STEP_UP),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedVerificationDoesNotMarkStepUpNorAudit() {
        org.mockito.Mockito.doThrow(new InvalidMfaCodeException())
                .when(mfaService).verifySecondFactor(MASTER_ID, "000000");

        assertThatThrownBy(() -> service().stepUp(MASTER_ID, "000000", "Bearer mst_token"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(sessionRepository, never()).markSteppedUp(any(), any());
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sessionNotFoundFailsClosed() {
        when(tokenGenerator.hash("mst_token")).thenReturn("hash");
        when(sessionRepository.findByTokenHash("hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().stepUp(MASTER_ID, "123456", "Bearer mst_token"))
                .isInstanceOf(MasterStepUpRequiredException.class);
        // O fator foi verificado (senha/TOTP ok) mas não há sessão a elevar.
        verify(mfaService).verifySecondFactor(MASTER_ID, "123456");
        verify(sessionRepository, never()).markSteppedUp(any(), any());
    }

    @Test
    void sessionOfAnotherMasterFailsClosed() {
        when(tokenGenerator.hash("mst_token")).thenReturn("hash");
        when(sessionRepository.findByTokenHash("hash"))
                .thenReturn(Optional.of(activeSession(UUID.randomUUID())));

        assertThatThrownBy(() -> service().stepUp(MASTER_ID, "123456", "Bearer mst_token"))
                .isInstanceOf(MasterStepUpRequiredException.class);
    }

    @Test
    void missingBearerFailsClosed() {
        assertThatThrownBy(() -> service().stepUp(MASTER_ID, "123456", null))
                .isInstanceOf(MasterStepUpRequiredException.class);
        verify(sessionRepository, never()).markSteppedUp(any(), any());
    }
}
