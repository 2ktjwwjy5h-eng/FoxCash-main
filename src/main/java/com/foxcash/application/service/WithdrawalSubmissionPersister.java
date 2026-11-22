package com.foxcash.application.service;

import com.foxcash.domain.exception.WithdrawalNotFoundException;
import com.foxcash.domain.model.withdrawal.Withdrawal;
import com.foxcash.domain.port.out.WithdrawalRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Fase B do saque (auditoria 2026-08-05/B-04): grava a {@code external_reference}
 * do PSP no saque {@code PROCESSING} em transação PRÓPRIA ({@code REQUIRES_NEW}).
 *
 * <p>O PSP é chamado FORA da transação de banco pelo {@link WithdrawalService}/
 * {@link MasterWithdrawalService} (a conexão do pool não fica retida durante o
 * I/O). Este bean persiste o resultado do aceite — se a transação principal
 * falhar depois, o saque continua {@code PROCESSING} sem referência e o retry
 * (reconciliação) reusa o MESMO {@code withdrawal.id} (idempotente no
 * adquirente — padrão {@code code} = id, Spec 28). Mesmo padrão do
 * {@link IdempotencyPersister}.
 */
@Component
public class WithdrawalSubmissionPersister {

    private final TransactionTemplate txTemplate;
    private final WithdrawalRepository repository;

    public WithdrawalSubmissionPersister(PlatformTransactionManager transactionManager,
                                         WithdrawalRepository repository) {
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.repository = repository;
    }

    /** Grava {@code externalReference}/{@code payoutProvider} no saque (REQUIRES_NEW). */
    public Withdrawal submit(UUID withdrawalId, String externalReference, String provider) {
        return txTemplate.execute(status -> {
            Withdrawal withdrawal = repository.findById(withdrawalId)
                    .orElseThrow(() -> new WithdrawalNotFoundException(withdrawalId));
            return repository.save(withdrawal.submitToPsp(externalReference, provider));
        });
    }
}
