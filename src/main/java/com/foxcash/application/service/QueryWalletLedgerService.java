package com.foxcash.application.service;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;
import com.foxcash.domain.port.in.QueryWalletLedgerUseCase;
import com.foxcash.domain.port.out.WalletLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Consulta do ledger do wallet (Spec 27) — somente leitura, paginado.
 */
@Service
public class QueryWalletLedgerService implements QueryWalletLedgerUseCase {

    private final WalletLedgerRepository repository;

    public QueryWalletLedgerService(WalletLedgerRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<WalletLedgerEntry> findByMerchantId(UUID merchantId,
                                                          WalletLedgerEntryType type,
                                                          Instant from, Instant to,
                                                          int page, int size) {
        return repository.findByMerchantId(merchantId, type, from, to, page, size);
    }
}
