package com.foxcash.domain.port.in;

import com.foxcash.domain.model.merchant.Merchant;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de entrada: consultas de lojistas.
 */
public interface QueryMerchantUseCase {

    Optional<Merchant> findById(UUID merchantId);
}
