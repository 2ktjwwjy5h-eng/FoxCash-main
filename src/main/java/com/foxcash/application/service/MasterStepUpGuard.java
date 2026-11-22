package com.foxcash.application.service;

import com.foxcash.domain.exception.MasterStepUpRequiredException;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.model.master.MasterSession;
import com.foxcash.domain.port.out.MasterSessionRepository;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import com.foxcash.infrastructure.config.MasterStepUpProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.UUID;

/**
 * Gate de STEP-UP MFA do master (Spec 69, F3): acao sensivel
 * ({@link MasterStepUpProperties#isRequired}) exige a sessao do request atual
 * com {@code stepped_up_at} dentro da janela (default 15 min). Ausente/vencida
 * → {@link MasterStepUpRequiredException} (403 {@code step_up_required}).
 *
 * <p>Chamado nos services (mesma transacao) junto com o
 * {@link MasterPermissionGuard} — protege HTTP + Operator Bot + futuro Master
 * Console. Fora de um request HTTP (sem token bearer) → falha fechada (nunca
 * concede step-up a um cliente que não apresente sessão).
 */
@Service
public class MasterStepUpGuard {

    private final MasterSessionRepository sessionRepository;
    private final SessionTokenGenerator tokenGenerator;
    private final MasterStepUpProperties properties;
    private final Clock clock;

    public MasterStepUpGuard(MasterSessionRepository sessionRepository,
                             SessionTokenGenerator tokenGenerator,
                             MasterStepUpProperties properties,
                             Clock clock) {
        this.sessionRepository = sessionRepository;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Exige step-up valido na sessao do request atual quando a permissao e
     * sensivel; caso contrario, no-op.
     */
    public void requireSteppedUp(Master actor,
                                 MasterPermission permission) {
        if (!properties.isRequired(permission)) {
            return;
        }
        MasterSession session = currentSession(actor.id());
        if (!session.isSteppedUp(clock.instant(), properties.getWindow())) {
            throw new MasterStepUpRequiredException();
        }
    }

    /** Sessao do request atual (Bearer token) pertencente ao master — fail-closed. */
    private MasterSession currentSession(UUID masterId) {
        String rawToken = currentBearerToken();
        if (rawToken == null) {
            throw new MasterStepUpRequiredException();
        }
        return sessionRepository.findByTokenHash(tokenGenerator.hash(rawToken))
                .filter(session -> session.masterId().equals(masterId))
                .filter(session -> session.isValid(clock.instant()))
                .orElseThrow(MasterStepUpRequiredException::new);
    }

    /** Token Bearer do request atual (null fora de request HTTP). */
    private String currentBearerToken() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet && servlet.getRequest() != null) {
            String authorization = servlet.getRequest().getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                String token = authorization.substring("Bearer ".length()).trim();
                if (!token.isBlank()) {
                    return token;
                }
            }
        }
        return null;
    }
}
