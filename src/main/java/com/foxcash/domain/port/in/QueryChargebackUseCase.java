package com.foxcash.domain.port.in;

import com.foxcash.domain.model.fraud.Chargeback;

import java.util.List;
import java.util.UUID;

/**
 * Porta de entrada: consulta de chargebacks (Spec 19) — revisao do master.
 */
public interface QueryChargebackUseCase {

    Chargeback findById(UUID chargebackId);

    List<Chargeback> findRecent(int limit);
}
