package com.foxcash.application.service;

import com.foxcash.domain.exception.DestinationInactiveException;
import com.foxcash.domain.exception.DestinationNotFoundException;
import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.withdrawal.AutoWithdrawConfig;
import com.foxcash.domain.model.withdrawal.WithdrawalDestination;
import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;
import com.foxcash.domain.port.out.AutoWithdrawConfigRepository;
import com.foxcash.domain.port.out.WithdrawalDestinationRepository;
import com.foxcash.infrastructure.config.AutoWithdrawProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link AutoWithdrawConfigService} (Spec 70): GET com defaults (nunca
 * 404), PUT upsert (cria/atualiza) e todas as validacoes (threshold 400,
 * min-threshold 400, destinationId obrigatorio 400, 404 destination_not_found,
 * 409 destination_inactive) + auditoria AUTO_WITHDRAW_SETTINGS_UPDATED na mesma
 * transacao.
 */
class AutoWithdrawConfigServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID DESTINATION_ID = UUID.randomUUID();

    private final AutoWithdrawConfigRepository configRepository = mock(AutoWithdrawConfigRepository.class);
    private final WithdrawalDestinationRepository destinationRepository = mock(WithdrawalDestinationRepository.class);
    private final PlatformActivityRecorder activityRecorder = mock(PlatformActivityRecorder.class);
    private final AutoWithdrawProperties properties = new AutoWithdrawProperties();

    private AutoWithdrawConfigService service;

    @BeforeEach
    void setUp() {
        service = new AutoWithdrawConfigService(configRepository, destinationRepository,
                activityRecorder, properties);
        when(configRepository.save(any(AutoWithdrawConfig.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private WithdrawalDestination activeDestination() {
        return WithdrawalDestination.create(MERCHANT_ID, WithdrawalDestinationType.PIX_CPF,
                "52998224725", null, null, null, null, "Conta pessoal", Instant.now());
    }

    private WithdrawalDestination inactiveDestination() {
        return activeDestination().deactivate(Instant.now());
    }

    // ---- GET ----

    @Test
    void getWithoutConfigReturnsDefaultsNever404() {
        when(configRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        AutoWithdrawConfigService.ConfigView view = service.get(MERCHANT_ID);

        assertThat(view.enabled()).isFalse();
        assertThat(view.threshold().amount()).isEqualByComparingTo("1000.00"); // default plataforma
        assertThat(view.destinationId()).isNull();
    }

    @Test
    void getWithConfigReflectsState() {
        AutoWithdrawConfig config = AutoWithdrawConfig.enable(MERCHANT_ID, Money.of("500.00"), DESTINATION_ID);
        when(configRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));

        AutoWithdrawConfigService.ConfigView view = service.get(MERCHANT_ID);

        assertThat(view.enabled()).isTrue();
        assertThat(view.threshold().amount()).isEqualByComparingTo("500.00");
        assertThat(view.destinationId()).isEqualTo(DESTINATION_ID);
    }

    // ---- PUT: criação ----

    @Test
    void updateCreatesEnabledConfigWhenAbsent() {
        when(configRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        when(destinationRepository.findByIdAndMerchantId(DESTINATION_ID, MERCHANT_ID))
                .thenReturn(Optional.of(activeDestination()));

        AutoWithdrawConfigService.ConfigView view = service.update(
                MERCHANT_ID, true, new BigDecimal("1000.00"), DESTINATION_ID);

        assertThat(view.enabled()).isTrue();
        assertThat(view.threshold().amount()).isEqualByComparingTo("1000.00");
        verify(configRepository).save(any(AutoWithdrawConfig.class));
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.AUTO_WITHDRAW_SETTINGS_UPDATED), eq("AUTO_WITHDRAW"),
                any(), eq(MERCHANT_ID), any());
    }

    @Test
    void updateDisabledWithoutPriorConfigIsNoOp() {
        when(configRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        AutoWithdrawConfigService.ConfigView view = service.update(
                MERCHANT_ID, false, new BigDecimal("750.00"), null);

        assertThat(view.enabled()).isFalse();
        assertThat(view.threshold().amount()).isEqualByComparingTo("750.00");
        assertThat(view.destinationId()).isNull();
        verify(configRepository, never()).save(any());
        verify(activityRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    // ---- PUT: atualização ----

    @Test
    void updateExistingConfigPersistsNewState() {
        AutoWithdrawConfig existing = AutoWithdrawConfig.enable(MERCHANT_ID, Money.of("1000.00"), DESTINATION_ID);
        when(configRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(existing));

        AutoWithdrawConfigService.ConfigView view = service.update(
                MERCHANT_ID, false, new BigDecimal("500.00"), DESTINATION_ID);

        assertThat(view.enabled()).isFalse();
        assertThat(view.threshold().amount()).isEqualByComparingTo("500.00");
        verify(configRepository).save(any(AutoWithdrawConfig.class));
        verify(activityRecorder).record(eq(PlatformActorType.MERCHANT), eq(MERCHANT_ID),
                eq(PlatformAction.AUTO_WITHDRAW_SETTINGS_UPDATED), eq("AUTO_WITHDRAW"),
                any(), eq(MERCHANT_ID), any());
    }

    @Test
    void updateExistingDisableWithoutDestinationKeepsPrevious() {
        // enabled=false sem destinationId MANTÉM o anterior (destination_id é
        // NOT NULL — "mantém o anterior"; o job ignora configs desabilitadas).
        AutoWithdrawConfig existing = AutoWithdrawConfig.enable(MERCHANT_ID, Money.of("1000.00"), DESTINATION_ID);
        when(configRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(existing));

        AutoWithdrawConfigService.ConfigView view = service.update(
                MERCHANT_ID, false, new BigDecimal("500.00"), null);

        assertThat(view.enabled()).isFalse();
        assertThat(view.destinationId()).isEqualTo(DESTINATION_ID); // mantido
        verify(configRepository).save(any(AutoWithdrawConfig.class));
    }

    // ---- PUT: validações de threshold ----

    @Test
    void updateWithoutThresholdThrows() {
        assertThatThrownBy(() -> service.update(MERCHANT_ID, false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold e obrigatorio");
    }

    @Test
    void updateWithZeroOrNegativeThresholdThrows() {
        assertThatThrownBy(() -> service.update(MERCHANT_ID, false, new BigDecimal("0.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maior que zero");
        assertThatThrownBy(() -> service.update(MERCHANT_ID, false, new BigDecimal("-1.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maior que zero");
    }

    @Test
    void updateBelowMinThresholdThrows() {
        assertThatThrownBy(() -> service.update(MERCHANT_ID, false, new BigDecimal("0.50"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abaixo do minimo");
    }

    // ---- PUT: validações de destino ----

    @Test
    void updateEnabledWithoutDestinationThrows() {
        assertThatThrownBy(() -> service.update(MERCHANT_ID, true, new BigDecimal("100.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destinationId");
    }

    @Test
    void updateEnabledWithForeignOrUnknownDestinationThrows404() {
        when(destinationRepository.findByIdAndMerchantId(DESTINATION_ID, MERCHANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                MERCHANT_ID, true, new BigDecimal("100.00"), DESTINATION_ID))
                .isInstanceOf(DestinationNotFoundException.class);
    }

    @Test
    void updateEnabledWithInactiveDestinationThrows409() {
        when(destinationRepository.findByIdAndMerchantId(DESTINATION_ID, MERCHANT_ID))
                .thenReturn(Optional.of(inactiveDestination()));

        assertThatThrownBy(() -> service.update(
                MERCHANT_ID, true, new BigDecimal("100.00"), DESTINATION_ID))
                .isInstanceOf(DestinationInactiveException.class);
    }

    @Test
    void updateDisabledIgnoresUnknownDestination() {
        // enabled=false não resolve o destino (pode desabilitar mesmo com o
        // destino removido — o job que valida no gatilho).
        when(configRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        AutoWithdrawConfigService.ConfigView view = service.update(
                MERCHANT_ID, false, new BigDecimal("100.00"), null);

        assertThat(view.enabled()).isFalse();
        verify(destinationRepository, never()).findByIdAndMerchantId(any(), any());
    }
}
