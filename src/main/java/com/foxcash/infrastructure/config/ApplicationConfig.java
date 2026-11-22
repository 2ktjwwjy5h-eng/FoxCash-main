package com.foxcash.infrastructure.config;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.service.InvoiceCalculator;
import com.foxcash.domain.service.PricingCalculator;
import com.foxcash.infrastructure.security.ApiKeySecurityProperties;
import com.foxcash.infrastructure.security.BodyInspectionProperties;
import com.foxcash.infrastructure.security.CryptoProperties;
import com.foxcash.infrastructure.security.RateLimitProperties;
import com.foxcash.infrastructure.security.TurnstileProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Beans de dominio/servicos compartilhados, desacoplados de adaptadores.
 */
@Configuration
@EnableConfigurationProperties({PricingProperties.class, CryptoProperties.class, PspProperties.class,
        MasterProperties.class, PortalProperties.class, RateLimitProperties.class, RiskProperties.class,
        FraudProperties.class, BillingProperties.class, NotificationProperties.class, AuthProperties.class,
        SchedulerProperties.class, ApiKeySecurityProperties.class, TurnstileProperties.class,
        SecurityAlertProperties.class, BodyInspectionProperties.class, MasterStepUpProperties.class,
        AutoWithdrawProperties.class, ConsoleProperties.class, BotProperties.class,
        BroadcastProperties.class, TrackingProperties.class, CrmProperties.class, SweepProperties.class,
        RoutingProperties.class, PvsProperties.class, MasterPanelProperties.class,
        FingerprintProperties.class, RabbitProperties.class})
public class ApplicationConfig {

    @Bean
    public PricingPlan pricingPlan(PricingProperties properties) {
        return new PricingPlan(
                Money.of(properties.getTransactionFee()),
                Money.of(properties.getMonthlyFee()));
    }

    @Bean
    public PricingCalculator pricingCalculator() {
        return new PricingCalculator();
    }

    @Bean
    public InvoiceCalculator invoiceCalculator(PricingCalculator pricingCalculator) {
        return new InvoiceCalculator(pricingCalculator);
    }

    /** Relogio unico da aplicacao (UTC) — injetado em servicos/filtros (Spec 13). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Validade das sessoes do master (Spec 13) — {@code foxcash.master.session-ttl-seconds}. */
    @Bean
    public Duration masterSessionTtl(MasterProperties properties) {
        return Duration.ofSeconds(properties.getSessionTtlSeconds());
    }

    /** Validade das sessoes do portal do lojista (Spec 20) — {@code foxcash.portal.session-ttl-seconds}. */
    @Bean
    public Duration merchantSessionTtl(PortalProperties properties) {
        return Duration.ofSeconds(properties.getSessionTtlSeconds());
    }

    /** Validade do token de recuperacao de senha (Spec 40) — {@code foxcash.auth.reset-token-ttl-seconds}. */
    @Bean
    public Duration resetTokenTtl(AuthProperties properties) {
        return Duration.ofSeconds(properties.getResetTokenTtlSeconds());
    }
}
