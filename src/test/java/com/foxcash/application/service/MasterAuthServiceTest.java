package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidCredentialsException;
import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterSession;
import com.foxcash.domain.port.in.MasterAuthUseCase;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.MasterSessionRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import com.foxcash.infrastructure.config.AuthProperties;
import com.foxcash.infrastructure.security.SessionTokenGeneratorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterAuthService} (Spec 13): login valida usuário/senha
 * (BCrypt) e emite sessão com token opaco (só o hash é persistido); logout
 * revoga a sessão pelo hash do token apresentado. Auditoria 2026-08-05 (B-08):
 * lockout de login após falhas seguidas.
 */
class MasterAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final Duration TTL = Duration.ofHours(8);
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final String PASSWORD_HASH = "$2a$10$abcdefghijklmnopqrstuv";

    private final MasterRepository masterRepository = mock(MasterRepository.class);
    private final MasterSessionRepository sessionRepository = mock(MasterSessionRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final SessionTokenGenerator tokenGenerator = new SessionTokenGeneratorImpl();
    private final MasterMfaService mfaService = mock(MasterMfaService.class);

    private MasterAuthService service;
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final AuthProperties authProperties = authProperties();
    private final MasterLoginLockoutPersister lockoutPersister = mock(MasterLoginLockoutPersister.class);
    private final SecurityAlertPublisher securityAlerts = mock(SecurityAlertPublisher.class);

    @BeforeEach
    void setUp() {
        service = new MasterAuthService(
                masterRepository, sessionRepository, passwordHasher, tokenGenerator,
                activityRecorder, mfaService, Clock.fixed(NOW, java.time.ZoneOffset.UTC), TTL,
                authProperties, lockoutPersister, securityAlerts);
    }

    private static AuthProperties authProperties() {
        AuthProperties properties = new AuthProperties();
        properties.setMaxLoginAttempts(5);
        properties.setLockoutMinutes(15);
        return properties;
    }

    private Master activeMaster() {
        return Master.restore(MASTER_ID, "master", PASSWORD_HASH, "Master Principal", true, NOW);
    }

    @Test
    void loginSuccessIssuesSessionWithOpaqueTokenAndPersistsOnlyHash() {
        Master master = activeMaster();
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(master));
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(master));
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);

        MasterAuthUseCase.LoginResult result = service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-certa"));

        // Token opaco no formato mst_<32 hex>; expira ~8h após o login.
        assertThat(result.token()).startsWith("mst_").hasSize(36);
        assertThat(result.masterId()).isEqualTo(MASTER_ID);
        assertThat(result.name()).isEqualTo("Master Principal");
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(TTL));

        // Somente o hash SHA-256 do token é persistido — nunca o texto claro.
        ArgumentCaptor<MasterSession> captor = ArgumentCaptor.forClass(MasterSession.class);
        verify(sessionRepository).save(captor.capture());
        MasterSession saved = captor.getValue();
        assertThat(saved.masterId()).isEqualTo(MASTER_ID);
        assertThat(saved.tokenHash()).isEqualTo(tokenGenerator.hash(result.token()));
        assertThat(saved.tokenHash()).isNotEqualTo(result.token());
        // Round 11 (getters/validade da sessão emitida): NegateConditionals L52
        // (2º operando exige sessão NÃO revogada), NullReturnVals L56/L68/L72
        // (id/createdAt/expiresAt).
        assertThat(saved.isValid(NOW)).isTrue();
        assertThat(saved.id()).isNotNull();
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void loginWithWrongPasswordThrowsUnauthorized() {
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(activeMaster()));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-errada")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void loginWithUnknownUsernameThrowsUnauthorized() {
        when(masterRepository.findByUsername("inexistente")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(
                MasterAuthUseCase.LoginCommand.of("inexistente", "qualquer")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void unknownUsernameRunsDummyBcryptToNormalizeTiming() {
        // B-01 (auditoria 2026-08-05): usuário inexistente executa o BCrypt dummy
        // (mesmo padrão do login do lojista) para o tempo de resposta ser uniforme
        // entre usuário inexistente e senha incorreta — sem oráculo por timing.
        when(masterRepository.findByUsername("inexistente")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(
                MasterAuthUseCase.LoginCommand.of("inexistente", "qualquer")))
                .isInstanceOf(InvalidCredentialsException.class);

        // O matches é executado com o hash dummy compartilhado (DummyCredentials).
        verify(passwordHasher).matches(eq("qualquer"),
                eq(com.foxcash.infrastructure.security.DummyCredentials.DUMMY_BCRYPT_HASH));
    }

    @Test
    void loginWithInactiveMasterThrowsUnauthorized() {
        Master inactive = Master.restore(MASTER_ID, "master", PASSWORD_HASH, "Master", false, NOW);
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(inactive));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-certa")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void logoutRevokesActiveSession() {
        // O serviço busca a sessão pelo HASH do token apresentado.
        String tokenHash = tokenGenerator.hash("mst_token_qualquer");
        MasterSession session = MasterSession.issue(MASTER_ID, tokenHash, NOW, NOW.plus(TTL));
        when(sessionRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(session));

        service.logout("mst_token_qualquer");

        ArgumentCaptor<MasterSession> captor = ArgumentCaptor.forClass(MasterSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().revokedAt()).isNotNull();
        assertThat(captor.getValue().revokedAt()).isEqualTo(NOW);
        // Mutation (BooleanTrueReturnVals L52): sessão revogada NÃO é válida.
        assertThat(captor.getValue().isValid(NOW)).isFalse();
    }

    @Test
    void logoutIsNoOpForBlankOrUnknownToken() {
        // Token em branco: não consulta nem salva nada.
        service.logout("  ");
        verify(sessionRepository, never()).findByTokenHash(anyString());
        verify(sessionRepository, never()).save(any());

        // Token desconhecido: consulta, mas não salva (nenhuma sessão para revogar).
        when(sessionRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        service.logout("mst_desconhecido");
        verify(sessionRepository, never()).save(any());
    }

    // ---------- Auditoria 2026-08-05 (B-08): lockout de login ----------

    @Test
    void repeatedFailedLoginsLockMasterAndEvenCorrectPasswordIsRejected() {
        Master master = activeMaster();
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(master));
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenAnswer(inv -> Optional.of(master));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);

        // 5 falhas seguidas → lockout disparado (lockedUntil = NOW + 15min).
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login(
                    MasterAuthUseCase.LoginCommand.of("master", "errada")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(master.isLocked(NOW)).isTrue();

        // Mesmo com a senha correta, durante a janela o login é recusado (401 uniforme).
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);
        assertThatThrownBy(() -> service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-certa")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void successfulLoginClearsFailedAttemptsAndLockout() {
        Master master = activeMaster();
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(master));
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(master));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);

        // 2 falhas: contador incrementado, ainda sem lockout.
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.login(
                    MasterAuthUseCase.LoginCommand.of("master", "errada")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(master.failedLoginAttempts()).isEqualTo(2);
        assertThat(master.isLocked(NOW)).isFalse();

        // Login válido limpa o contador (persistido antes da sessão).
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);
        service.login(MasterAuthUseCase.LoginCommand.of("master", "senha-certa"));

        assertThat(master.failedLoginAttempts()).isZero();
        assertThat(master.lockedUntil()).isNull();
    }

    @Test
    void loginSucceedsAfterLockoutWindowExpires() {
        // Lockout expirado (lockedUntil no passado) + senha correta → login ok.
        Master master = Master.restore(MASTER_ID, "master", PASSWORD_HASH, "Master", true,
                false, NOW, 0, NOW.minusSeconds(1));
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(master));
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(master));
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);

        MasterAuthUseCase.LoginResult result = service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-certa"));

        assertThat(result.masterId()).isEqualTo(MASTER_ID);
    }

    @Test
    void failedLoginPersistsThroughRequiresNewPersister() {
        // O throw dentro do @Transactional do login faria rollback de um save
        // comum — a falha precisa ser persistida em transação PRÓPRIA (B-08).
        // SEC-01: o persister recebe id + config (re-lê a linha com FOR UPDATE).
        Master master = activeMaster();
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(master));
        when(passwordHasher.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(
                MasterAuthUseCase.LoginCommand.of("master", "errada")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(lockoutPersister).record(MASTER_ID, NOW, 5, Duration.ofMinutes(15));
    }

    // ---------- Spec 44 (2FA): login exige o 2º fator com MFA ativo ----------

    @Test
    void loginWithoutTotpCodeThrowsUnauthorizedWhenMfaActive() {
        Master master = activeMaster();
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(master));
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(master));
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);
        when(mfaService.hasActiveMfa(MASTER_ID)).thenReturn(true);
        // Sem código → verifySecondFactor(masterId, null) lança 401 uniforme.
        doThrow(new InvalidMfaCodeException()).when(mfaService).verifySecondFactor(MASTER_ID, null);

        assertThatThrownBy(() -> service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-certa")))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void loginWithWrongTotpCodeThrowsUnauthorizedWhenMfaActive() {
        Master master = activeMaster();
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(master));
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(master));
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);
        when(mfaService.hasActiveMfa(MASTER_ID)).thenReturn(true);
        doThrow(new InvalidMfaCodeException()).when(mfaService).verifySecondFactor(MASTER_ID, "000000");

        assertThatThrownBy(() -> service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-certa", "000000")))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void loginWithValidTotpCodeIssuesSession() {
        Master master = activeMaster();
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(master));
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(master));
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);
        when(mfaService.hasActiveMfa(MASTER_ID)).thenReturn(true);

        MasterAuthUseCase.LoginResult result = service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-certa", "123456"));

        assertThat(result.token()).startsWith("mst_").hasSize(36);
        verify(mfaService).verifySecondFactor(MASTER_ID, "123456");
        verify(sessionRepository).save(any());
    }

    @Test
    void loginWithoutMfaActiveSkipsSecondFactor() {
        // Sem TOTP ativo o login comporta-se exatamente como antes (nenhuma regressão).
        Master master = activeMaster();
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(master));
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(master));
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);
        when(mfaService.hasActiveMfa(MASTER_ID)).thenReturn(false);

        MasterAuthUseCase.LoginResult result = service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-certa"));

        assertThat(result.token()).startsWith("mst_").hasSize(36);
        verify(mfaService, never()).verifySecondFactor(eq(MASTER_ID), any());
    }

    // ---------- SEC-15 (revisão de segurança 2026-08-11): sucesso × lockout ----------

    @Test
    void successfulLoginDoesNotClearLockoutAppliedByConcurrentFailure() {
        // SEC-15: o reset do contador acontece sobre o estado FRESCO (FOR UPDATE).
        // A snapshot carregada no início do request pode estar SEM lockout, mas se
        // uma falha CONCORRENTE já aplicou o lockout no banco, o login válido NÃO
        // pode salvar a snapshot stale por cima — o lockout prevalece (401).
        Master staleSnapshot = activeMaster(); // sem lockout (estado antigo)
        Master lockedInDb = Master.restore(MASTER_ID, "master", PASSWORD_HASH, "Master", true,
                false, NOW, 5, NOW.plusSeconds(900)); // 5 falhas → bloqueado
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(staleSnapshot));
        // O banco (estado fresco sob FOR UPDATE) já está bloqueado.
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(lockedInDb));
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-certa")))
                .isInstanceOf(InvalidCredentialsException.class);

        // O save NÃO acontece (a snapshot stale não pode apagar o lockout do banco).
        verify(masterRepository, never()).save(any(Master.class));
        verify(sessionRepository, never()).save(any());
    }

    // ---------- SEC-04 (revisão 2026-08-11): conta inativa SEM oráculo de timing ----------

    @Test
    void inactiveMasterStillRunsBcrypt() {
        // SEC-04: o matches é avaliado ANTES do if — o short-circuit `||` (conta
        // inativa) não pode pular o BCrypt. Conta inativa e senha incorreta
        // respondem no MESMO tempo (~BCrypt), sem oráculo de ativação por timing.
        Master inactive = Master.restore(MASTER_ID, "master", PASSWORD_HASH, "Master", false, NOW);
        when(masterRepository.findByUsername("master")).thenReturn(Optional.of(inactive));
        when(passwordHasher.matches("senha-certa", PASSWORD_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.login(
                MasterAuthUseCase.LoginCommand.of("master", "senha-certa")))
                .isInstanceOf(InvalidCredentialsException.class);

        // O BCrypt rodou mesmo com a conta inativa (uniformidade de tempo).
        verify(passwordHasher).matches("senha-certa", PASSWORD_HASH);
    }

    // ---------- SEC-25 (revisão de segurança 2026-08-11): troca de senha do master ----------

    @Test
    void changePasswordUpdatesHashAndRevokesAllSessions() {
        // SEC-25: a troca exige a senha ATUAL (BCrypt), persiste o novo hash e
        // revoga TODAS as sessões do master — a rotação mata tokens roubados no
        // instante da troca (mesmo padrão do reset do lojista, Spec 40).
        Master master = activeMaster();
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(master));
        when(passwordHasher.matches("senha-atual", PASSWORD_HASH)).thenReturn(true);
        when(passwordHasher.hash("Nova-Senha-123")).thenReturn("$2a$10$novo-hash-bcrypt");

        service.changePassword(MASTER_ID, "senha-atual", "Nova-Senha-123");

        // O hash novo é persistido (o domínio nunca vê a senha em claro).
        ArgumentCaptor<Master> captor = ArgumentCaptor.forClass(Master.class);
        verify(masterRepository).save(captor.capture());
        assertThat(captor.getValue().passwordHash()).isEqualTo("$2a$10$novo-hash-bcrypt");
        // Sessões revogadas (inclusive a atual — re-login obrigatório).
        verify(sessionRepository).revokeAllByMasterId(MASTER_ID, NOW);
        // Trilha de atividade para rastreabilidade da rotação.
        verify(activityRecorder).record(eq(PlatformActorType.MASTER), eq(MASTER_ID),
                eq(PlatformAction.MASTER_PASSWORD_CHANGED), eq("MASTER"), eq(MASTER_ID),
                isNull(), any(Map.class));
    }

    @Test
    void changePasswordWithWrongCurrentPasswordThrowsUnauthorized() {
        Master master = activeMaster();
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(master));
        when(passwordHasher.matches("senha-errada", PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(
                MASTER_ID, "senha-errada", "Nova-Senha-123"))
                .isInstanceOf(InvalidCredentialsException.class);

        // Nenhum efeito: sem save do master, sem revogação de sessões.
        verify(masterRepository, never()).save(any(Master.class));
        verify(sessionRepository, never()).revokeAllByMasterId(any(), any());
    }

    @Test
    void changePasswordRejectsWeakNewPasswordBeforeAnyMutation() {
        Master master = activeMaster();
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(master));

        // Sem maiúscula → viola a política (PasswordPolicy) ANTES de qualquer
        // mutação — nem o matches da senha atual roda (fail-fast de contrato).
        assertThatThrownBy(() -> service.changePassword(
                MASTER_ID, "senha-atual", "someminusculas123"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(masterRepository, never()).save(any(Master.class));
        verify(sessionRepository, never()).revokeAllByMasterId(any(), any());
    }

    @Test
    void changePasswordOfInactiveMasterStillRunsBcrypt() {
        // SEC-04 no caminho da troca: conta inativa NÃO pode pular o BCrypt da
        // senha atual (oráculo de ativação por timing) — o matches roda sempre.
        Master inactive = Master.restore(MASTER_ID, "master", PASSWORD_HASH, "Master", false, NOW);
        when(masterRepository.findByIdForUpdate(MASTER_ID)).thenReturn(Optional.of(inactive));
        when(passwordHasher.matches("senha-atual", PASSWORD_HASH)).thenReturn(true);

        assertThatThrownBy(() -> service.changePassword(
                MASTER_ID, "senha-atual", "Nova-Senha-123"))
                .isInstanceOf(InvalidCredentialsException.class);

        // O BCrypt rodou mesmo com a conta inativa (uniformidade de tempo).
        verify(passwordHasher).matches("senha-atual", PASSWORD_HASH);
        verify(masterRepository, never()).save(any(Master.class));
        verify(sessionRepository, never()).revokeAllByMasterId(any(), any());
    }
}
