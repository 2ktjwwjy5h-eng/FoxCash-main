package com.foxcash.application.service;

import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.port.out.MasterRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistência do estado de lockout do master (auditoria 2026-08-05/B-08).
 *
 * <p>O login falho lança {@code InvalidCredentialsException} DENTRO do
 * {@code @Transactional} do {@link MasterAuthService} — um save comum seria
 * revertido pelo rollback da transação. Este bean grava o contador/lockout em
 * transação PRÓPRIA ({@code REQUIRES_NEW}) ANTES do throw, garantindo que a
 * falha fica registrada mesmo com o rollback da ação. Mesmo padrão do
 * {@link IdempotencyPersister} (Spec 28).
 *
 * <p><b>SEC-01 (revisão de segurança 2026-08-11):</b> o incremento do contador
 * passou a ser <b>atômico</b> — o persister re-lê a linha com lock pessimista
 * ({@code findByIdForUpdate}) e re-aplica {@link Master#registerFailedLogin}
 * sobre o estado FRESCO do banco. Antes, o incremento acontecia apenas na
 * entidade em memória do request e era gravado por {@code save} (read-modify-
 * write): N requisições paralelas liam o mesmo contador e sobrescreviam umas às
 * outras (lost update), permitindo burlar o lockout com brute-force paralelo.
 * Se outra requisição concorrente já disparou o lockout, a tentativa não é
 * contada (a janela não é estendida por tentativas que caíram dentro dela).
 */
@Component
public class MasterLoginLockoutPersister {

    private final TransactionTemplate txTemplate;
    private final MasterRepository repository;

    public MasterLoginLockoutPersister(PlatformTransactionManager transactionManager,
                                       MasterRepository repository) {
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.repository = repository;
    }

    /**
     * Registra a falha de login do master de forma ATÔMICA (REQUIRES_NEW +
     * lock pessimista na linha). Se o lockout já foi disparado por outra
     * requisição concorrente, a tentativa não é contada (sem extensão da janela).
     *
     * @param masterId        id do master que falhou
     * @param now             instante da tentativa
     * @param maxAttempts     limite de falhas ({@code foxcash.auth.max-login-attempts})
     * @param lockoutDuration duração do lockout ({@code foxcash.auth.lockout-minutes})
     */
    public void record(UUID masterId, Instant now, int maxAttempts, Duration lockoutDuration) {
        txTemplate.executeWithoutResult(status ->
                repository.findByIdForUpdate(masterId)
                        .filter(master -> !master.isLocked(now))
                        .ifPresent(master -> {
                            master.registerFailedLogin(now, maxAttempts, lockoutDuration);
                            repository.save(master);
                        }));
    }
}
