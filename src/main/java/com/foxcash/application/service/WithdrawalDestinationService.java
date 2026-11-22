package com.foxcash.application.service;

import com.foxcash.domain.exception.DestinationNotFoundException;
import com.foxcash.domain.exception.TooManyDestinationsException;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.port.in.WithdrawalDestinationUseCase;
import com.foxcash.domain.port.out.WithdrawalDestinationRepository;
import com.foxcash.domain.service.WithdrawalDestinationValidator;
import com.foxcash.infrastructure.config.PortalProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Gestão de destinos de saque do lojista (Spec 31). Privado por lojista: toda
 * operação é escopada pelo merchantId do principal; destino de outro lojista
 * responde 404 (sem revelar existência). Criação valida tipo/formato e o
 * limite de destinos ativos (configurável, default 5).
 */
@Service
public class WithdrawalDestinationService implements WithdrawalDestinationUseCase {

    private final WithdrawalDestinationRepository repository;
    private final PortalProperties portalProperties;

    public WithdrawalDestinationService(WithdrawalDestinationRepository repository,
                                        PortalProperties portalProperties) {
        this.repository = repository;
        this.portalProperties = portalProperties;
    }

    @Override
    @Transactional
    public WithdrawalDestination create(UUID merchantId, Command command) {
        // Validação do tipo/formato (domínio) antes de qualquer persistência.
        WithdrawalDestinationValidator.validate(command.type(), command.value(),
                command.bankCode(), command.agency(), command.account());

        if (repository.countActiveByMerchantId(merchantId)
                >= portalProperties.getMaxWithdrawalDestinations()) {
            throw new TooManyDestinationsException(portalProperties.getMaxWithdrawalDestinations());
        }

        WithdrawalDestination destination = WithdrawalDestination.create(
                merchantId, command.type(), command.value(), command.bankCode(),
                command.agency(), command.account(), command.accountType(),
                command.label(), Instant.now());
        return repository.save(destination);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WithdrawalDestination> list(UUID merchantId) {
        return repository.findByMerchantId(merchantId);
    }

    @Override
    @Transactional
    public WithdrawalDestination updateLabel(UUID merchantId, UUID destinationId, String label) {
        WithdrawalDestination destination = requireOwned(merchantId, destinationId);
        return repository.save(destination.withLabel(label, Instant.now()));
    }

    @Override
    @Transactional
    public WithdrawalDestination setActive(UUID merchantId, UUID destinationId, boolean active) {
        WithdrawalDestination destination = requireOwned(merchantId, destinationId);
        WithdrawalDestination updated = active
                ? destination.activate(Instant.now())
                : destination.deactivate(Instant.now());
        return repository.save(updated);
    }

    private WithdrawalDestination requireOwned(UUID merchantId, UUID destinationId) {
        return repository.findByIdAndMerchantId(destinationId, merchantId)
                .orElseThrow(() -> new DestinationNotFoundException(destinationId));
    }
}
