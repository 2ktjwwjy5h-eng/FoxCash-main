package com.foxcash.domain.port.in;

import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;

import java.util.List;
import java.util.UUID;

/**
 * Caso de uso: gestão de destinos de saque do lojista (Spec 31), exclusiva do
 * portal ({@code ROLE_MERCHANT_PORTAL}). O merchantId vem do principal — nunca
 * do cliente. Os valores sensíveis são devolvidos apenas no domínio (o
 * controller mascara na resposta; o banco guarda criptografado).
 */
public interface WithdrawalDestinationUseCase {

    /**
     * Cria um destino ATIVO após validar tipo/formato e o limite de ativos.
     *
     * @throws IllegalArgumentException formato inválido / tipo inválido → 400
     * @throws com.foxcash.domain.exception.TooManyDestinationsException limite de
     *         ativos atingido → 409 {@code too_many_destinations}
     */
    WithdrawalDestination create(UUID merchantId, Command command);

    /** Lista os destinos do lojista (o controller aplica a máscara). */
    List<WithdrawalDestination> list(UUID merchantId);

    /** Atualiza apenas o rótulo. 404 se o destino não pertence ao lojista. */
    WithdrawalDestination updateLabel(UUID merchantId, UUID destinationId, String label);

    /** Ativa/desativa o destino. 404 se não pertence ao lojista. */
    WithdrawalDestination setActive(UUID merchantId, UUID destinationId, boolean active);

    record Command(WithdrawalDestinationType type, String value, String bankCode,
                   String agency, String account, String accountType, String label) {
    }
}
