package com.foxcash.infrastructure.outbound.pix;

import com.foxcash.infrastructure.config.PspProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provedores mock extras para o roteamento (Spec 91 — E2E): além do
 * {@code mockPixProviderClient} ({@link MockPixProviderClient}, bean
 * {@code @Component}), registra um 2º adquirente simulado ({@code "suitpay"} —
 * adapter futuro) para os cenários de fallback/isolamento por policy do
 * {@code PixProviderRouter}. A simulação dinâmica do mock A ({@code "mock"}) é
 * controlada pelo endpoint dev {@code /internal/psp-mock}; o "suitpay" é sempre
 * limpo (nunca simula falha por config).
 */
@Configuration
public class PixMockProvidersConfig {

    @Bean(name = "suitpayPixProviderClient")
    public MockPixProviderClient suitpayPixProviderClient(PspProperties pspProperties) {
        return new MockPixProviderClient(pspProperties, "suitpay");
    }
}
