package com.foxcash.application.service;

import com.foxcash.domain.model.transaction.PixTransaction;
import com.foxcash.domain.port.out.TransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistência antecipada da cobrança (Spec 28): grava a transação
 * {@code PENDING} com a chave de idempotência em transação PRÓPRIA
 * ({@code REQUIRES_NEW}) ANTES da chamada ao PSP.
 *
 * <p>Bean separado evita o problema de self-invocation de proxy
 * {@code @Transactional}: mesmo se o fluxo principal der rollback (timeout/erro
 * do PSP), o save inicial JÁ FOI commitado e a chave fica registrada — o retry
 * com a mesma chave nunca duplica. Retorna o {@link PixTransaction} com a chave
 * atribuída: o chamador reassina {@code tx = persistPending(tx, key)} para que
 * os saves seguintes (attach/fail) preservem a coluna {@code idempotency_key}.
 */
@Component
public class IdempotencyPersister {

    private final TransactionTemplate txTemplate;
    private final TransactionRepository repository;

    public IdempotencyPersister(PlatformTransactionManager transactionManager,
                                TransactionRepository repository) {
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.repository = repository;
    }

    public PixTransaction persistPending(PixTransaction transaction, String idempotencyKey) {
        return txTemplate.execute(status ->
                repository.save(transaction.withIdempotencyKey(idempotencyKey)));
    }
}
