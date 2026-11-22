package com.foxcash.application.service;

import com.foxcash.domain.exception.MasterStepUpRequiredException;
import com.foxcash.domain.model.audit.MasterAuditAction;
import com.foxcash.domain.model.master.MasterSession;
import com.foxcash.domain.port.out.MasterSessionRepository;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import com.foxcash.infrastructure.security.ClientIpResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.util.UUID;

/**
 * Step-up MFA do master (Spec 69, F3): re-prova de posse via
 * {@code POST /api/v1/master/mfa/step-up}. Reusa o
 * {@link MasterMfaService#verifySecondFactor} existente (TOTP anti-replay +
 * recovery code single-use + lockout de 5 falhas) e, no sucesso, grava
 * {@code stepped_up_at} na sessao do request (janela 15 min — D4.1).
 *
 * <p>Falha de verificacao → excecao do MFA (401/403) SEM gravar step-up (a
 * persistencia da sessao acontece apenas apos o {@code verifySecondFactor}).
 * O step-up em si gera entrada de auditoria {@code MASTER_STEP_UP} (D4.4).
 */
@Service
public class MasterStepUpService {

    private final MasterMfaService mfaService;
    private final MasterSessionRepository sessionRepository;
    private final SessionTokenGenerator tokenGenerator;
    private final MasterAuditRecorder auditRecorder;
    private final ClientIpResolver clientIpResolver;
    private final Clock clock;

    public MasterStepUpService(MasterMfaService mfaService,
                               MasterSessionRepository sessionRepository,
                               SessionTokenGenerator tokenGenerator,
                               MasterAuditRecorder auditRecorder,
                               ClientIpResolver clientIpResolver,
                               Clock clock) {
        this.mfaService = mfaService;
        this.sessionRepository = sessionRepository;
        this.tokenGenerator = tokenGenerator;
        this.auditRecorder = auditRecorder;
        this.clientIpResolver = clientIpResolver;
        this.clock = clock;
    }

    /**
     * Valida o 2º fator e eleva a sessao atual do master.
     *
     * @param authorization header {@code Authorization} (Bearer) da requisicao —
     *                      identifica a sessao a elevar
     */
    @Transactional
    public void stepUp(UUID masterId, String code, String authorization) {
        // Reusa a verificacao do 2º fator (anti-replay + recovery + lockout) —
        // falha propaga (401/403) e NADA e gravado (rollback do step-up).
        mfaService.verifySecondFactor(masterId, code);

        MasterSession session = resolveSession(masterId, authorization);
        sessionRepository.markSteppedUp(session.id(), clock.instant());

        auditRecorder.record(masterId, MasterAuditAction.MASTER_STEP_UP, null,
                "MASTER", masterId, null, null, null, currentIp());
    }

    private MasterSession resolveSession(UUID masterId, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new MasterStepUpRequiredException();
        }
        String rawToken = authorization.substring("Bearer ".length()).trim();
        if (rawToken.isBlank()) {
            throw new MasterStepUpRequiredException();
        }
        return sessionRepository.findByTokenHash(tokenGenerator.hash(rawToken))
                .filter(session -> session.masterId().equals(masterId))
                .filter(session -> session.isValid(clock.instant()))
                .orElseThrow(MasterStepUpRequiredException::new);
    }

    private String currentIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet) {
            return clientIpResolver.resolve(servlet.getRequest());
        }
        return "unknown";
    }
}
