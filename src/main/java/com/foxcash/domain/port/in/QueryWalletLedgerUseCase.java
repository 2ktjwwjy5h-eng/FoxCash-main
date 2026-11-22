package com.foxcash.domain.port.in;

import com.foxcash.domain.model.PageResult;
import com.foxcash.domain.model.wallet.WalletLedgerEntry;
import com.foxcash.domain.model.wallet.WalletLedgerEntryType;

import java.time.Instant;
import java.util.UUID;

/**
 * Caso de uso: consulta do ledger do wallet (Spec 27) — somente leitura.
 * Usado pelo master (auditoria de qualquer lojista) e pelo portal do lojista
 * (somente o próprio — o escopo é garantido pelo controller, que usa o
 * principal em vez de um parâmetro de lojista).
 */
public interface QueryWalletLedgerUseCase {

    PageResult<WalletLedgerEntry> findByMerchantId(UUID merchantId,
                                                   WalletLedgerEntryType type,
                                                   Instant from, Instant to,
                                                   int page, int size);
}
