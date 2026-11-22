package com.foxcash.application.service;

import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.exception.MasterNotFoundException;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterMfaMethod;
import com.foxcash.domain.model.master.MasterRecoveryCode;
import com.foxcash.domain.model.master.MasterTotp;
import com.foxcash.domain.port.in.MasterMfaUseCase;
import com.foxcash.domain.port.out.MasterMfaMethodRepository;
import com.foxcash.domain.port.out.MasterRecoveryCodeRepository;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.MasterTotpRepository;
import com.foxcash.domain.port.out.PasswordHasher;
import com.foxcash.domain.service.Totp;
import com.foxcash.infrastructure.security.CryptoConverter;
import com.foxcash.infrastructure.security.DocumentHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplicação: gestão do MFA do master (Spec 44) — ativação em 2 passos (prova
 * de posse do app), desativação com posse (senha + TOTP/recovery), regeneração
 * de recovery codes e verificação do 2º fator no login.
 *
 * <p><b>Segurança (decisões 2026-08-06):</b> secret TOTP criptografado em
 * repouso (CryptoConverter AES-256/GCM — hash puro tornaria a verificação
 * impossível); recovery codes SHA-256 single-use; comparações em tempo
 * constante ({@code MessageDigest.isEqual}); auditoria Spec 37/41 com 7 eventos
 * MFA (sem {@code MFA_UNLOCKED} — o lockout expira sozinho).
 */
@Service
public class MasterMfaService implements MasterMfaUseCase {

    /** Recovery codes gerados na ativação/regeneração. */
    private static final int RECOVERY_COUNT = 8;

    /** TTL do estado PENDING (confirmar a posse do app). */
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    /** Máximo de falhas de TOTP antes do lockout (mesmo padrão do login B-08). */
    private static final int MAX_TOTP_ATTEMPTS = 5;

    /** Duração do lockout de TOTP (expira sozinho — sem unlock manual). */
    private static final Duration TOTP_LOCKOUT = Duration.ofMinutes(15);

    private final MasterRepository masterRepository;
    private final MasterMfaMethodRepository methodRepository;
    private final MasterTotpRepository totpRepository;
    private final MasterRecoveryCodeRepository recoveryCodeRepository;
    private final PasswordHasher passwordHasher;
    private final CryptoConverter cryptoConverter;
    private final MasterTotpFailurePersister failurePersister;
    private final PlatformActivityRecorder activityRecorder;
    private final Clock clock;

    public MasterMfaService(MasterRepository masterRepository,
                            MasterMfaMethodRepository methodRepository,
                            MasterTotpRepository totpRepository,
                            MasterRecoveryCodeRepository recoveryCodeRepository,
                            PasswordHasher passwordHasher,
                            CryptoConverter cryptoConverter,
                            MasterTotpFailurePersister failurePersister,
                            PlatformActivityRecorder activityRecorder,
                            Clock clock) {
        this.masterRepository = masterRepository;
        this.methodRepository = methodRepository;
        this.totpRepository = totpRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.passwordHasher = passwordHasher;
        this.cryptoConverter = cryptoConverter;
        this.failurePersister = failurePersister;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ActivateResult activate(UUID masterId) {
        Master master = requireMaster(masterId);
        // Não pode haver método ativo nem pendente para ativar de novo.
        if (methodRepository.findActiveByMasterId(masterId).isPresent()) {
            throw new IllegalStateException("MFA ja ativado");
        }
        methodRepository.findPendingByMasterId(masterId).ifPresent(m ->
                methodRepository.deleteByMasterId(masterId));

        Instant now = clock.instant();
        UUID methodId = UUID.randomUUID();
        String secret = Totp.generateSecret();

        MasterMfaMethod method = MasterMfaMethod.pending(methodId, masterId, now, now.plus(PENDING_TTL));
        methodRepository.save(method);
        // Secret criptografado em repouso (AES-256/GCM) — o domínio guarda o
        // ciphertext; a decifração acontece apenas na verificação do código.
        totpRepository.save(MasterTotp.create(methodId, cryptoConverter.convertToDatabaseColumn(secret), now));

        return new ActivateResult(Totp.otpauthUri(master.username(), secret), secret);
    }

    @Override
    @Transactional
    public ConfirmResult confirm(UUID masterId, String code) {
        MasterMfaMethod method = methodRepository.findPendingByMasterId(masterId)
                .orElseThrow(InvalidMfaCodeException::new);
        Instant now = clock.instant();
        if (method.isPendingExpired(now)) {
            methodRepository.deleteByMasterId(masterId);
            throw new InvalidMfaCodeException();
        }
        MasterTotp totp = totpRepository.findByMethodId(method.id())
                .orElseThrow(InvalidMfaCodeException::new);
        String secret = cryptoConverter.convertToEntityAttribute(totp.secretEncrypted());
        if (!Totp.verify(secret, code, now)) {
            throw new InvalidMfaCodeException();
        }

        methodRepository.save(method.activate(now));
        List<String> recoveryCodes = generateRecoveryCodes(method.id(), now);

        activityRecorder.record(PlatformActorType.MASTER, masterId, PlatformAction.MFA_ACTIVATED,
                "MASTER", masterId, null, null);
        return new ConfirmResult(recoveryCodes);
    }

    @Override
    @Transactional
    public void deactivate(UUID masterId, String code, String password) {
        Master master = requireMaster(masterId);
        if (!passwordHasher.matches(password, master.passwordHash())) {
            throw new InvalidMfaCodeException();
        }
        verifySecondFactor(masterId, code);

        methodRepository.deleteByMasterId(masterId);
        activityRecorder.record(PlatformActorType.MASTER, masterId, PlatformAction.MFA_DEACTIVATED,
                "MASTER", masterId, null, null);
    }

    @Override
    @Transactional
    public List<String> regenerateRecoveryCodes(UUID masterId, String code) {
        verifySecondFactor(masterId, code);
        MasterMfaMethod method = methodRepository.findActiveByMasterId(masterId)
                .orElseThrow(InvalidMfaCodeException::new);

        recoveryCodeRepository.deleteByMethodId(method.id());
        List<String> codes = generateRecoveryCodes(method.id(), clock.instant());

        activityRecorder.record(PlatformActorType.MASTER, masterId,
                PlatformAction.MFA_RECOVERY_REGENERATED, "MASTER", masterId, null, null);
        return codes;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveMfa(UUID masterId) {
        return methodRepository.findActiveByMasterId(masterId).isPresent();
    }

    /**
     * Verifica o 2º fator (login): TOTP válido (janela ±1, tempo constante,
     * anti-replay) OU recovery code single-use. Aplica lockout de TOTP (5 falhas
     * → 15min, expira sozinho) com 401 uniforme (sem oráculo). Auditoria
     * MFA_LOGIN_SUCCESS/FAILED/LOCKED.
     */
    @Transactional
    public void verifySecondFactor(UUID masterId, String code) {
        MasterMfaMethod method = methodRepository.findActiveByMasterId(masterId)
                .orElseThrow(InvalidMfaCodeException::new);
        Instant now = clock.instant();
        MasterTotp totp = totpRepository.findByMethodId(method.id())
                .orElseThrow(InvalidMfaCodeException::new);

        // Lockout ativo → 401 uniforme (expira sozinho — sem unlock manual).
        if (totp.isLocked(now)) {
            throw new InvalidMfaCodeException();
        }

        // 1) Tenta recovery code primeiro (tempo constante via hash).
        if (code != null && code.startsWith("mrc_")) {
            String hash = DocumentHasher.hash(code);
            // SEC-20 (revisão de segurança 2026-08-11): consume ATÔMICO (UPDATE
            // condicional no banco) — marca o código como usado SOMENTE se ainda
            // estiver sem uso. Antes era leitura + saveAll: dois logins
            // concorrentes com o MESMO recovery code ambos liam UNUSED e ambos
            // passavam — o single-use era burlado sob corrida (mesma classe do
            // SEC-10, já corrigido no reset token com consumeIfOpen).
            if (recoveryCodeRepository.consumeIfOpen(method.id(), hash, now)) {
                activityRecorder.record(PlatformActorType.MASTER, masterId,
                        PlatformAction.MFA_RECOVERY_USED, "MASTER", masterId, null, null);
                activityRecorder.record(PlatformActorType.MASTER, masterId,
                        PlatformAction.MFA_LOGIN_SUCCESS, "MASTER", masterId, null, null);
                totpRepository.save(totp.recordValidCode(null, now));
                return;
            }
        }

        // 2) TOTP: verifica o código (tempo constante, janela ±1) + anti-replay.
        // Código ausente (login 2FA incompleto) → mesmo caminho de falha: 401
        // uniforme, sem NPE no hash (409 vazaria que o código faltou — oráculo).
        String secret = cryptoConverter.convertToEntityAttribute(totp.secretEncrypted());
        String codeHash = (code == null) ? null : DocumentHasher.hash(code);
        boolean replay = totp.lastCodeHash() != null && codeHash != null
                && MessageDigest.isEqual(totp.lastCodeHash().getBytes(StandardCharsets.UTF_8),
                        codeHash.getBytes(StandardCharsets.UTF_8));
        if (replay || !Totp.verify(secret, code, now)) {
            // Auditoria 2026-08-06: persiste a falha/lockout em transação PRÓPRIA
            // (REQUIRES_NEW) — o throw abaixo reverteria o save/evento comuns na
            // transação do login, tornando o lockout de TOTP inócuo (padrão B-08).
            // SEC-01: o persister re-lê a linha com FOR UPDATE e re-aplica a falha
            // sobre o estado FRESCO — incremento atômico (sem lost update entre
            // verificações paralelas, o que burlaria o lockout de TOTP).
            failurePersister.recordFailure(masterId, method.id(), now,
                    MAX_TOTP_ATTEMPTS, TOTP_LOCKOUT.toMinutes());
            throw new InvalidMfaCodeException();
        }

        totpRepository.save(totp.recordValidCode(codeHash, now));
        activityRecorder.record(PlatformActorType.MASTER, masterId, PlatformAction.MFA_LOGIN_SUCCESS,
                "MASTER", masterId, null, null);
    }

    private List<String> generateRecoveryCodes(UUID methodId, Instant now) {
        List<MasterRecoveryCode> codes = new ArrayList<>();
        List<String> plaintext = new ArrayList<>();
        for (int i = 0; i < RECOVERY_COUNT; i++) {
            String code = "mrc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            codes.add(MasterRecoveryCode.create(UUID.randomUUID(), methodId,
                    DocumentHasher.hash(code), now));
            plaintext.add(code);
        }
        recoveryCodeRepository.saveAll(codes);
        return plaintext;
    }

    private Master requireMaster(UUID masterId) {
        return masterRepository.findById(masterId)
                .orElseThrow(() -> new MasterNotFoundException(masterId));
    }
}
