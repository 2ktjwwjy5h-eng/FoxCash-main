package com.foxcash.application.service;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.activity.PlatformAction;
import com.foxcash.domain.model.activity.PlatformActorType;
import com.foxcash.domain.model.activity.PlatformActivity;
import com.foxcash.domain.model.activity.PlatformActivityQuery;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.in.MerchantDashboardUseCase;
import com.foxcash.domain.port.out.BotLeadRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.PlatformActivityRepository;
import com.foxcash.infrastructure.config.PortalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PortalShellService} (Spec 101) — saudação por hora do dia,
 * billing com progresso (cap 100 via Spec 21), recent-leads com iniciais,
 * notificações vazias → [] e escopo por perfil (Spec 124).
 */
class PortalShellServiceTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final PricingPlan PLAN = PricingPlan.fixed();
    private static final Instant NOW = Instant.parse("2026-08-15T02:00:00Z");

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final MerchantDashboardUseCase dashboard = mock(MerchantDashboardUseCase.class);
    private final BotLeadRepository leadRepository = mock(BotLeadRepository.class);
    private final PlatformActivityRepository activityRepository = mock(PlatformActivityRepository.class);
    private final PortalProperties portalProperties = new PortalProperties();

    private PortalShellService service;

    @BeforeEach
    void setUp() {
        // Clock fixo em 2026-08-15 02:00 UTC — sem hora "cheia" indevida.
        service = new PortalShellService(merchantRepository, dashboard, leadRepository,
                activityRepository, portalProperties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(
                Merchant.restore(MERCHANT_ID, "Gansa", "gansa@test.local", null, null,
                        MerchantStatus.ACTIVE, PLAN, Money.of("0.00"), false, NOW)));
    }

    private com.foxcash.domain.model.PageResult<PlatformActivity> emptyActivities() {
        return com.foxcash.domain.model.PageResult.of(List.of(), 0, 10, 0);
    }

    @Test
    void greetingSalutationMatchesHourWindow() {
        assertThat(PortalShellService.salutationFor(2)).isEqualTo("Boa madrugada");
        assertThat(PortalShellService.salutationFor(5)).isEqualTo("Boa madrugada");
        assertThat(PortalShellService.salutationFor(6)).isEqualTo("Boa dia");
        assertThat(PortalShellService.salutationFor(11)).isEqualTo("Boa dia");
        assertThat(PortalShellService.salutationFor(12)).isEqualTo("Boa tarde");
        assertThat(PortalShellService.salutationFor(17)).isEqualTo("Boa tarde");
        assertThat(PortalShellService.salutationFor(18)).isEqualTo("Boa noite");
        assertThat(PortalShellService.salutationFor(23)).isEqualTo("Boa noite");
    }

    @Test
    void greetingUsesDisplayOffsetForDateAndSalutation() {
        when(dashboard.summary(any(), any(), any(), any())).thenReturn(
                new MerchantDashboardUseCase.Summary(Money.of("0.00"), 0, BigDecimal.ZERO, 0));
        when(leadRepository.findRecentByMerchantId(any(), any(), anyInt())).thenReturn(List.of());
        when(activityRepository.query(any(PlatformActivityQuery.class))).thenReturn(emptyActivities());

        // 02:00 UTC + offset -03:00 → 23:00 do dia anterior → "Boa noite".
        PortalShellService.PortalShell shell =
                service.shell(MERCHANT_ID, null, null, ZoneOffset.ofHours(-3));

        assertThat(shell.greeting().salutation()).isEqualTo("Boa noite");
        assertThat(shell.greeting().name()).isEqualTo("Gansa");
        assertThat(shell.greeting().date()).isEqualTo("14/08/2026");
        assertThat(shell.greeting().time()).isEqualTo("23:00");
    }

    @Test
    void billingUsesDashboardSummaryAndGoal() {
        when(dashboard.summary(eq(MERCHANT_ID), eq(null), any(), any())).thenReturn(
                new MerchantDashboardUseCase.Summary(Money.of("293.58"), 5,
                        BigDecimal.valueOf(50.0), 29));
        when(leadRepository.findRecentByMerchantId(any(), any(), anyInt())).thenReturn(List.of());
        when(activityRepository.query(any(PlatformActivityQuery.class))).thenReturn(emptyActivities());

        PortalShellService.PortalShell shell =
                service.shell(MERCHANT_ID, null, null, ZoneOffset.UTC);

        assertThat(shell.billing().billedAmount()).isEqualByComparingTo("293.58");
        // Default do PortalProperties (Spec 21): 1000.00.
        assertThat(shell.billing().goalAmount()).isEqualByComparingTo("1000.00");
        assertThat(shell.billing().progressPercent()).isEqualTo(29);
    }

    @Test
    void billingEmptyIsZero() {
        when(dashboard.summary(eq(MERCHANT_ID), eq(null), any(), any())).thenReturn(
                new MerchantDashboardUseCase.Summary(Money.of("0.00"), 0, BigDecimal.ZERO, 0));
        when(leadRepository.findRecentByMerchantId(any(), any(), anyInt())).thenReturn(List.of());
        when(activityRepository.query(any(PlatformActivityQuery.class))).thenReturn(emptyActivities());

        PortalShellService.PortalShell shell =
                service.shell(MERCHANT_ID, null, null, ZoneOffset.UTC);

        assertThat(shell.billing().billedAmount()).isEqualByComparingTo("0.00");
        assertThat(shell.billing().progressPercent()).isZero();
    }

    @Test
    void recentLeadsOrderedAndWithInitials() {
        when(leadRepository.findRecentByMerchantId(eq(MERCHANT_ID), eq(null), eq(8)))
                .thenReturn(List.of(
                        new BotLeadRepository.RecentLead(UUID.randomUUID(), "Joao Silva", NOW),
                        new BotLeadRepository.RecentLead(UUID.randomUUID(), "Maria", NOW.minusSeconds(60))));

        var leads = service.recentLeads(MERCHANT_ID, null, 8);

        assertThat(leads).hasSize(2);
        assertThat(leads.get(0).initials()).isEqualTo("JS");
        assertThat(leads.get(1).initials()).isEqualTo("MA");
    }

    @Test
    void recentLeadsEmptyWhenNoLeads() {
        when(leadRepository.findRecentByMerchantId(eq(MERCHANT_ID), eq(null), eq(8)))
                .thenReturn(List.of());

        var leads = service.recentLeads(MERCHANT_ID, null, 8);

        assertThat(leads).isEmpty();
    }

    @Test
    void notificationsEmptyWhenNoActivity() {
        when(dashboard.summary(any(), any(), any(), any())).thenReturn(
                new MerchantDashboardUseCase.Summary(Money.of("0.00"), 0, BigDecimal.ZERO, 0));
        when(leadRepository.findRecentByMerchantId(any(), any(), anyInt())).thenReturn(List.of());
        when(activityRepository.query(any(PlatformActivityQuery.class))).thenReturn(emptyActivities());

        PortalShellService.PortalShell shell =
                service.shell(MERCHANT_ID, null, null, ZoneOffset.UTC);

        assertThat(shell.notifications().unreadCount()).isZero();
        assertThat(shell.notifications().items()).isEmpty();
    }

    @Test
    void notificationsFromMerchantActivityLog() {
        when(dashboard.summary(any(), any(), any(), any())).thenReturn(
                new MerchantDashboardUseCase.Summary(Money.of("0.00"), 0, BigDecimal.ZERO, 0));
        when(leadRepository.findRecentByMerchantId(any(), any(), anyInt())).thenReturn(List.of());
        PlatformActivity sale = PlatformActivity.record(PlatformActorType.MERCHANT, MERCHANT_ID,
                PlatformAction.SALE_PAID, "TRANSACTION", UUID.randomUUID(), MERCHANT_ID,
                "{}", null, NOW);
        when(activityRepository.query(any(PlatformActivityQuery.class)))
                .thenReturn(com.foxcash.domain.model.PageResult.of(List.of(sale), 0, 10, 1));

        PortalShellService.PortalShell shell =
                service.shell(MERCHANT_ID, null, null, ZoneOffset.UTC);

        assertThat(shell.notifications().unreadCount()).isEqualTo(1);
        assertThat(shell.notifications().items().get(0).type()).isEqualTo("SALE_PAID");
        assertThat(shell.notifications().items().get(0).message()).isEqualTo("Venda aprovada");
    }

    @Test
    void initialsTwoLetters() {
        assertThat(PortalShellService.initials("Joao Silva")).isEqualTo("JS");
        assertThat(PortalShellService.initials("Maria")).isEqualTo("MA");
        assertThat(PortalShellService.initials("  Ana  Souza  ")).isEqualTo("AS");
        assertThat(PortalShellService.initials(null)).isEqualTo("?");
        assertThat(PortalShellService.initials("  ")).isEqualTo("?");
    }
}
