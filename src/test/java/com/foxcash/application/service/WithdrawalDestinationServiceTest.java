package com.foxcash.application.service;

import com.foxcash.domain.exception.DestinationNotFoundException;
import com.foxcash.domain.exception.TooManyDestinationsException;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;
import com.foxcash.domain.port.in.WithdrawalDestinationUseCase;
import com.foxcash.domain.port.out.WithdrawalDestinationRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link WithdrawalDestinationService} (Spec 31): CRUD privado por
 * lojista, validação de formato, limite de ativos (409) e 404 cross-loja.
 */
class WithdrawalDestinationServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID OTHER_MERCHANT_ID = UUID.randomUUID();

    private final WithdrawalDestinationRepository repository = mock(WithdrawalDestinationRepository.class);
    private final PortalProperties properties = new PortalProperties();

    private WithdrawalDestinationService service;

    @BeforeEach
    void setUp() {
        properties.setMaxWithdrawalDestinations(5);
        service = new WithdrawalDestinationService(repository, properties);
    }

    private WithdrawalDestinationUseCase.Command cpfCommand(String value, String label) {
        return new WithdrawalDestinationUseCase.Command(WithdrawalDestinationType.PIX_CPF,
                value, null, null, null, null, label);
    }

    @Test
    void createsValidDestination() {
        when(repository.countActiveByMerchantId(MERCHANT_ID)).thenReturn(0L);
        when(repository.save(any(WithdrawalDestination.class))).thenAnswer(inv -> inv.getArgument(0));

        WithdrawalDestination created = service.create(MERCHANT_ID, cpfCommand("52998224725", "Minha conta"));

        assertThat(created.type()).isEqualTo(WithdrawalDestinationType.PIX_CPF);
        assertThat(created.active()).isTrue();
        ArgumentCaptor<WithdrawalDestination> captor = ArgumentCaptor.forClass(WithdrawalDestination.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().value()).isEqualTo("52998224725");
    }

    @Test
    void rejectsInvalidFormat() {
        assertThatThrownBy(() -> service.create(MERCHANT_ID, cpfCommand("123", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsWhenActiveLimitReached() {
        when(repository.countActiveByMerchantId(MERCHANT_ID)).thenReturn(5L);

        assertThatThrownBy(() -> service.create(MERCHANT_ID, cpfCommand("52998224725", null)))
                .isInstanceOf(TooManyDestinationsException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void listsOnlyMerchantDestinations() {
        WithdrawalDestination d = WithdrawalDestination.create(MERCHANT_ID, WithdrawalDestinationType.PIX_EVP,
                "e6a2a9f0-8f00-4a9a-9b9c-1d2e3f4a5b6c", null, null, null, null, null, java.time.Instant.now());
        when(repository.findByMerchantId(MERCHANT_ID)).thenReturn(List.of(d));

        assertThat(service.list(MERCHANT_ID)).hasSize(1);
    }

    @Test
    void updatesLabel() {
        WithdrawalDestination d = WithdrawalDestination.create(MERCHANT_ID, WithdrawalDestinationType.PIX_CPF,
                "52998224725", null, null, null, null, "Antigo", java.time.Instant.now());
        when(repository.findByIdAndMerchantId(d.id(), MERCHANT_ID)).thenReturn(Optional.of(d));
        when(repository.save(any(WithdrawalDestination.class))).thenAnswer(inv -> inv.getArgument(0));

        WithdrawalDestination updated = service.updateLabel(MERCHANT_ID, d.id(), "Novo rótulo");

        assertThat(updated.label()).isEqualTo("Novo rótulo");
    }

    @Test
    void updateOfAnotherMerchantDestinationThrowsNotFound() {
        UUID destinationId = UUID.randomUUID();
        when(repository.findByIdAndMerchantId(destinationId, MERCHANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateLabel(MERCHANT_ID, destinationId, "x"))
                .isInstanceOf(DestinationNotFoundException.class);
        assertThatThrownBy(() -> service.setActive(MERCHANT_ID, destinationId, true))
                .isInstanceOf(DestinationNotFoundException.class);
    }

    @Test
    void togglesActive() {
        WithdrawalDestination d = WithdrawalDestination.create(MERCHANT_ID, WithdrawalDestinationType.PIX_CPF,
                "52998224725", null, null, null, null, null, java.time.Instant.now());
        when(repository.findByIdAndMerchantId(d.id(), MERCHANT_ID)).thenReturn(Optional.of(d));
        when(repository.save(any(WithdrawalDestination.class))).thenAnswer(inv -> inv.getArgument(0));

        WithdrawalDestination inactive = service.setActive(MERCHANT_ID, d.id(), false);
        assertThat(inactive.active()).isFalse();

        WithdrawalDestination reactivated = service.setActive(MERCHANT_ID, d.id(), true);
        assertThat(reactivated.active()).isTrue();
    }
}
