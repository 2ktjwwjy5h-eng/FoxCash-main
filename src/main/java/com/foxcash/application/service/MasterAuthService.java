package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidCredentialsException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterSession;
import com.foxcash.domain.port.in.MasterAuthUseCase;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.MasterSessionRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import com.foxcash.domain.service.PasswordPolicy;
import com.foxcash.infrastructure.config.AuthProperties;
import com.foxcash.infrastructure.security.DummyCredentials;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Aplicacao: autenticacao do perfil master (Spec 13, ADR-007).
 *
 * <p>Login valida usuario/senha (BCrypt), emite sessao com token opaco (somente
 * o hash SHA-256 e persistido) com expiracao configuravel. Logout revoga a
 * sessao pelo hash do token apresentado.
 *
 * <p>Auditoria 2026-08-05 (B-08): lockout de login — falhas seguidas de senha
 * incrementam o contador persistido do master; ao atingir o limite
 * ({@code foxcash.auth.max-login-attempts}) o login é recusado (401 uniforme)
 * por {@code foxcash.auth.lockout-minutes}. Usuario inexistente continua 401
 * sem contador (impossível persistir por conta) — coberto pelo rate limit por
 * IP da rota (RateLimitFilter).
 */
@Service
public class MasterAuthService implements MasterAuthUseCase {

    private final MasterRepository masterRepository;
    private final MasterSessionRepository sessionRepository;
    private final PasswordHasher passwordHasher;
    private final SessionTokenGenerator tokenGenerator;
    private final PlatformActivityRecorder activityRecorder;
    private final MasterMfaService mfaService;
    private final Clock clock;
    private final Duration sessionTtl;
    private final AuthProperties authProperties;
    private final MasterLoginLockoutPersister lockoutPersister;
    private final SecurityAlertPublisher securityAlerts;

    public MasterAuthService(MasterRepository masterRepository,
                             MasterSessionRepository sessionRepository,
                             PasswordHasher passwordHasher,
                             SessionTokenGenerator tokenGenerator,
                             PlatformActivityRecorder activityRecorder,
                             MasterMfaService mfaService,
                             Clock clock,
                             @Qualifier("masterSessionTtl") Duration sessionTtl,
                             AuthProperties authProperties,
                             MasterLoginLockoutPersister lockoutPersister,
                             SecurityAlertPublisher securityAlerts) {
        this.masterRepository = masterRepository;
        this.sessionRepository = sessionRepository;
        this.passwordHasher = passwordHasher;
        this.tokenGenerator = tokenGenerator;
        this.activityRecorder = activityRecorder;
        this.mfaService = mfaService;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
        this.authProperties = authProperties;
        this.lockoutPersister = lockoutPersister;
        this.securityAlerts = securityAlerts;
    }

    @Override
    @Transactional
    public LoginResult login(LoginCommand command) {
        Master master = masterRepository.findByUsername(command.username())
                .orElseThrow(() -> {
                    // B-01 (auditoria 2026-08-05): anti-enumeração por timing — executa o
                    // BCrypt dummy TAMBÉM quando o usuário não existe, mantendo o tempo de
                    // resposta uniforme entre usuário inexistente e senha incorreta.
                    passwordHasher.matches(command.password(), DummyCredentials.DUMMY_BCRYPT_HASH);
                    // Spec 64: falha de login (usuário inexistente) → contador de rajada.
                    securityAlerts.recordLoginFailure("master");
                    return new InvalidCredentialsException();
                });

        Instant now = clock.instant();
        // Senha PRIMEIRO (SEC-04, revisão de segurança 2026-08-11): o BCrypt roda
        // ANTES do check de lockout — conta bloqueada e senha incorreta respondem
        // no MESMO tempo (~BCrypt). Antes, o check de lockout antes do BCrypt
        // fazia a conta bloqueada responder ~1ms (oráculo de timing para o
        // atacante detectar o fim da janela e retomar o brute-force).
        // O matches é avaliado em variável ANTES do if: o short-circuit `||`
        // (conta inativa) não pode pular o BCrypt — conta inativa e senha
        // incorreta respondem no mesmo tempo (~BCrypt), sem oráculo de ativação.
        boolean passwordMatches = passwordHasher.matches(command.password(), master.passwordHash());
        if (!master.isActive() || !passwordMatches) {
            master.registerFailedLogin(now, authProperties.getMaxLoginAttempts(),
                    Duration.ofMinutes(authProperties.getLockoutMinutes()));
            // REQUIRES_NEW (B-08): o throw abaixo faria rollback de um save comum
            // no @Transactional do login — a falha precisa ser persistida mesmo
            // com o rollback da ação (padrão IdempotencyPersister, Spec 28).
            // SEC-01: o persister re-lê a linha com FOR UPDATE e re-aplica a falha
            // sobre o estado FRESCO — incremento atômico (sem lost update entre
            // tentativas paralelas, o que burlaria o lockout).
            lockoutPersister.record(master.id(), now,
                    authProperties.getMaxLoginAttempts(),
                    Duration.ofMinutes(authProperties.getLockoutMinutes()));
            securityAlerts.recordLoginFailure("master");
            throw new InvalidCredentialsException();
        }

        // SEC-15 (revisão de segurança 2026-08-11): o check de lockout e o reset
        // do contador acontecem sobre o estado FRESCO do banco (FOR UPDATE), não
        // sobre a snapshot carregada no início do request. Sem a re-leitura, um
        // login VÁLIDO que carregou o master antes de uma falha concorrente
        // aplicar o lockout salvaria a snapshot stale (contador/lockedUntil
        // antigos) por cima do lockout recém-aplicado — lost update inverso do
        // SEC-01 que burlaria a janela. A conta bloqueada com senha correta
        // também responde 401 (o lockout prevalece; o tempo já foi uniformizado
        // pelo BCrypt acima — SEC-04).
        Master fresh = masterRepository.findByIdForUpdate(master.id())
                .orElseThrow(InvalidCredentialsException::new);
        if (fresh.isLocked(now)) {
            securityAlerts.recordLoginFailure("master");
            throw new InvalidCredentialsException();
        }

        // Login válido: limpa contador/lockout sobre o estado FRESCO.
        fresh.registerSuccessfulLogin();
        masterRepository.save(fresh);

        // Spec 44 (2FA): com MFA ativo, a senha NÃO basta — exige o 2º fator
        // (TOTP ou recovery code). 401 uniforme sem código/código inválido.
        if (mfaService.hasActiveMfa(master.id())) {
            mfaService.verifySecondFactor(master.id(), command.totpCode());
        }

        activityRecorder.record(PlatformActorType.MASTER, master.id(), PlatformAction.MASTER_LOGIN,
                "MASTER", master.id(), null, Map.of("username", master.username()));

        SessionTokenGenerator.TokenPair token = tokenGenerator.generate();
        Instant expiresAt = now.plus(sessionTtl);
        MasterSession session = MasterSession.issue(master.id(), token.hash(), now, expiresAt);
        sessionRepository.save(session);

        return new LoginResult(token.plaintext(), master.id(), master.name(), expiresAt);
    }

    @Override
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        sessionRepository.findByTokenHash(tokenGenerator.hash(rawToken))
                .ifPresent(session -> {
                    session.revoke(clock.instant());
                    sessionRepository.save(session);
                    activityRecorder.record(PlatformActorType.MASTER, session.masterId(),
                            PlatformAction.MASTER_LOGOUT, "MASTER", session.masterId(), null, null);
                });
    }

    /**
     * SEC-25 (revisão de segurança 2026-08-11): troca de senha do master —
     * fecha o ciclo de vida da credencial (antes a senha gerada no create —
     * 64 bits — era imutável: sem troca, sem reset, sem rotação possível).
     * Exige a senha ATUAL (BCrypt SEMPRE roda — conta inativa e senha incorreta
     * respondem no mesmo tempo, mesmo padrão do SEC-04), aplica a política e
     * revoga TODAS as sessões do master — a rotação mata tokens roubados no
     * instante da troca (mesmo padrão do reset do lojista, Spec 40).
     */
    @Override
    @Transactional
    public void changePassword(UUID masterId, String currentPassword, String newPassword) {
        PasswordPolicy.validate(newPassword);
        Master master = masterRepository.findByIdForUpdate(masterId)
                .orElseThrow(InvalidCredentialsException::new);
        // BCrypt avaliado em variável ANTES do if: o short-circuit `||` (conta
        // inativa) não pode pular o BCrypt — sem oráculo de ativação por timing.
        boolean currentMatches = passwordHasher.matches(currentPassword, master.passwordHash());
        if (!master.isActive() || !currentMatches) {
            securityAlerts.recordLoginFailure("master");
            throw new InvalidCredentialsException();
        }
        master.changePassword(passwordHasher.hash(newPassword));
        masterRepository.save(master);
        // A rotação invalida TODAS as sessões (inclusive a atual — re-login
        // obrigatório após a troca, padrão do reset do lojista).
        sessionRepository.revokeAllByMasterId(masterId, clock.instant());
        activityRecorder.record(PlatformActorType.MASTER, masterId,
                PlatformAction.MASTER_PASSWORD_CHANGED, "MASTER", masterId, null,
                Map.of("username", master.username()));
    }
}
