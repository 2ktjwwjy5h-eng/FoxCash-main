package com.foxcash.application.service;

import com.foxcash.domain.exception.MasterStepUpRequiredException;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.model.master.MasterSession;
import com.foxcash.domain.port.out.MasterSessionRepository;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import com.foxcash.infrastructure.config.MasterStepUpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do gate de step-up MFA (Spec 69, F3 — critérios 6/8): ação sensível
 * exige a sessão do request com {@code stepped_up_at} dentro da janela; ação
 * NÃO sensível passa sem sessão; ausente/vencida/sessão de outro master/sem
 * request → 403 {@code step_up_required} (fail-closed).
 */
class MasterStepUpGuardTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID OTHER_MASTER_ID = UUID.randomUUID();

    private final MasterSessionRepository sessionRepository = mock(MasterSessionRepository.class);
    private final SessionTokenGenerator tokenGenerator = mock(SessionTokenGenerator.class);
    private final MasterStepUpProperties properties = new MasterStepUpProperties();
    private final Clock clock = Clock.fixed(NOW, java.time.ZoneOffset.UTC);

    private MasterStepUpGuard guard() {
        return new MasterStepUpGuard(sessionRepository, tokenGenerator, properties, clock);
    }

    private Master actor() {
        return Master.restore(MASTER_ID, "master", "hash", "Master", true, false, NOW);
    }

    private void withRequest(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/master/mfa/step-up");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void nonSensitivePermissionSkipsStepUpWithoutAnySession() {
        when(tokenGenerator.hash("mst_token")).thenReturn("hash");
        withRequest("Bearer mst_token");
        // merchant.suspend NÃO está em required-permissions → no-op mesmo sem sessão.
        assertThatCode(() -> guard().requireSteppedUp(actor(), MasterPermission.MERCHANT_SUSPEND))
                .doesNotThrowAnyException();
    }

    @Test
    void sensitivePermissionRequiresSteppedUpSession() {
        MasterSession session = MasterSession.restore(UUID.randomUUID(), MASTER_ID, "hash",
                NOW.minusSeconds(60), NOW.plusSeconds(3600), null, NOW);
        when(tokenGenerator.hash("mst_token")).thenReturn("hash");
        when(sessionRepository.findByTokenHash("hash")).thenReturn(Optional.of(session));
        withRequest("Bearer mst_token");

        assertThatCode(() -> guard().requireSteppedUp(actor(), MasterPermission.RECEIVABLE_WRITE_OFF))
                .doesNotThrowAnyException();
    }

    @Test
    void sensitivePermissionWithoutStepUpThrows403() {
        MasterSession session = MasterSession.restore(UUID.randomUUID(), MASTER_ID, "hash",
                NOW.minusSeconds(60), NOW.plusSeconds(3600), null, null); // sem stepped_up_at
        when(tokenGenerator.hash("mst_token")).thenReturn("hash");
        when(sessionRepository.findByTokenHash("hash")).thenReturn(Optional.of(session));
        withRequest("Bearer mst_token");

        assertThatThrownBy(() -> guard().requireSteppedUp(actor(), MasterPermission.RECEIVABLE_WRITE_OFF))
                .isInstanceOf(MasterStepUpRequiredException.class);
    }

    @Test
    void expiredWindowThrows403() {
        // stepped_up_at há 16 min → janela (15 min) vencida.
        MasterSession session = MasterSession.restore(UUID.randomUUID(), MASTER_ID, "hash",
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), null, NOW.minus(Duration.ofMinutes(16)));
        when(tokenGenerator.hash("mst_token")).thenReturn("hash");
        when(sessionRepository.findByTokenHash("hash")).thenReturn(Optional.of(session));
        withRequest("Bearer mst_token");

        assertThatThrownBy(() -> guard().requireSteppedUp(actor(), MasterPermission.INVOICE_COLLECT))
                .isInstanceOf(MasterStepUpRequiredException.class);
    }

    @Test
    void revokedSessionThrows403() {
        MasterSession session = MasterSession.restore(UUID.randomUUID(), MASTER_ID, "hash",
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), NOW, NOW); // revogada
        when(tokenGenerator.hash("mst_token")).thenReturn("hash");
        when(sessionRepository.findByTokenHash("hash")).thenReturn(Optional.of(session));
        withRequest("Bearer mst_token");

        assertThatThrownBy(() -> guard().requireSteppedUp(actor(), MasterPermission.RECEIVABLE_WRITE_OFF))
                .isInstanceOf(MasterStepUpRequiredException.class);
    }

    @Test
    void sessionOfAnotherMasterThrows403() {
        MasterSession session = MasterSession.restore(UUID.randomUUID(), OTHER_MASTER_ID, "hash",
                NOW.minusSeconds(60), NOW.plusSeconds(3600), null, NOW);
        when(tokenGenerator.hash("mst_token")).thenReturn("hash");
        when(sessionRepository.findByTokenHash("hash")).thenReturn(Optional.of(session));
        withRequest("Bearer mst_token");

        assertThatThrownBy(() -> guard().requireSteppedUp(actor(), MasterPermission.RECEIVABLE_WRITE_OFF))
                .isInstanceOf(MasterStepUpRequiredException.class);
    }

    @Test
    void noCurrentRequestFailsClosed() {
        // Sem request HTTP (job/bot sem token) → nunca concede step-up.
        assertThatThrownBy(() -> guard().requireSteppedUp(actor(), MasterPermission.RECEIVABLE_WRITE_OFF))
                .isInstanceOf(MasterStepUpRequiredException.class);
    }

    @Test
    void missingAuthorizationHeaderFailsClosed() {
        withRequest(null);
        assertThatThrownBy(() -> guard().requireSteppedUp(actor(), MasterPermission.RECEIVABLE_WRITE_OFF))
                .isInstanceOf(MasterStepUpRequiredException.class);
    }
}
