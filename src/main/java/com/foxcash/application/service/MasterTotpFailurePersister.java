package com.foxcash.application.service;

import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.master.MasterTotp;
import com.foxcash.domain.port.out.MasterTotpRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistência da FALHA de verificação do 2º fator de MFA (Spec 44, auditoria
 * 2026-08-06).
 *
 * <p>O {@code verifySecondFactor} lança {@code InvalidMfaCodeException} DENTRO
 * do {@code @Transactional} do login ({@link MasterAuthService}) — um save
 * comum de {@code failed_attempts}/{@code locked_until} e o evento de falha
 * seriam REVERTIDOS pelo rollback da transação, tornando o lockout de TOTP
 * (5 falhas → 15min) inócuo. Este bean grava a falha em transação PRÓPRIA
 * ({@code REQUIRES_NEW}) ANTES do throw — mesmo padrão do
 * {@link MasterLoginLockoutPersister} (B-08) e {@code IdempotencyPersister}
 * (Spec 28).
 *
 * <p><b>SEC-01 (revisão de segurança 2026-08-11):</b> o incremento de
 * {@code failed_attempts} passou a ser <b>atômico</b> — o persister re-lê a
 * linha com lock pessimista ({@code findByIdForUpdate}) e re-aplica
 * {@link MasterTotp#recordFailedAttempt} sobre o estado FRESCO do banco. Antes,
 * o estado era calculado na entidade em memória do request e gravado por
 * {@code save} (read-modify-write): N verificações paralelas liam o mesmo
 * contador e sobrescreviam umas às outras (lost update), permitindo burlar o
 * lockout de TOTP. Se outra verificação concorrente já disparou o lockout, a
 * tentativa não é contada (a janela não é estendida por tentativas que caíram
 * dentro dela). O evento {@code MFA_LOGIN_FAILED}/{@code MFA_LOCKED} é decidido
 * pelo estado resultante da falha.
 */
@Component
public class MasterTotpFailurePersister {

    private final TransactionTemplate txTemplate;
    private final MasterTotpRepository totpRepository;
    private final PlatformActivityRecorder activityRecorder;

    public MasterTotpFailurePersister(PlatformTransactionManager transactionManager,
                                      MasterTotpRepository totpRepository,
                                      PlatformActivityRecorder activityRecorder) {
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.totpRepository = totpRepository;
        this.activityRecorder = activityRecorder;
    }

    /**
     * Registra a falha de TOTP de forma ATÔMICA (REQUIRES_NEW + lock pessimista
     * na linha): incremento de {@code failed_attempts} (lockout se atingir o
     * máximo) + evento {@code MFA_LOGIN_FAILED}/{@code MFA_LOCKED}. Sobrevive ao
     * rollback da ação (login/desativação). Se o lockout já foi disparado por
     * outra verificação concorrente, a tentativa não é contada.
     *
     * @param masterId       master autenticando
     * @param methodId       método TOTP (id da linha {@code master_totp})
     * @param now            instante da tentativa
     * @param maxAttempts    máximo de falhas antes do lockout (default 5)
     * @param lockoutMinutes duração do lockout em minutos (default 15)
     */
    public void recordFailure(UUID masterId, UUID methodId, Instant now,
                              int maxAttempts, long lockoutMinutes) {
        txTemplate.executeWithoutResult(status ->
                totpRepository.findByIdForUpdate(methodId)
                        .filter(totp -> !totp.isLocked(now))
                        .ifPresent(totp -> {
                            MasterTotp failed = totp.recordFailedAttempt(maxAttempts, lockoutMinutes, now);
                            totpRepository.save(failed);
                            activityRecorder.record(PlatformActorType.MASTER, masterId,
                                    failed.isLocked(now)
                                            ? PlatformAction.MFA_LOCKED
                                            : PlatformAction.MFA_LOGIN_FAILED,
                                    "MASTER", masterId, null, null);
                        }));
    }
}
