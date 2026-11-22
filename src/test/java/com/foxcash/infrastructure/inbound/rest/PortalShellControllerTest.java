package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.PortalShellService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST do shell do portal (Spec 101): o merchantId vem do
 * principal (ROLE_MERCHANT_PORTAL), o fuso é lido do header
 * {@code X-Timezone-Offset} (inválido → 400 invalid_argument) e o escopo do
 * perfil (Spec 124) é resolvido via {@link ProfileScopeResolver}.
 */
class PortalShellControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final PortalShellService shellService = mock(PortalShellService.class);
    private final ProfileScopeResolver scopeResolver = mock(ProfileScopeResolver.class);
    private final Authentication authentication = mock(Authentication.class);

    private PortalShellController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalShellController(shellService, scopeResolver);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
        // Sem header → perfil ausente + botIds null (lojista inteiro — regressão Spec 124).
        when(scopeResolver.scopeProfileId(any(), any())).thenReturn(Optional.empty());
        when(scopeResolver.scopeBotIds(any(), any())).thenReturn(null);
    }

    private PortalShellService.PortalShell sampleShell() {
        return new PortalShellService.PortalShell(
                new PortalShellService.Greeting("Boa dia", "Gansa", "15/08/2026", "09:30"),
                new PortalShellService.Billing(Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-08-15T09:30:00Z"),
                        BigDecimal.valueOf(293.58), BigDecimal.valueOf(1000.00), 29),
                List.of(new PortalShellService.ShellLead(UUID.randomUUID(), "Joao Silva",
                        "JS", Instant.parse("2026-08-15T02:00:00Z"))),
                new PortalShellService.Notifications(1, List.of(
                        new PortalShellService.NotificationItem(UUID.randomUUID(),
                                "SALE_PAID", "Venda aprovada", Instant.parse("2026-08-15T01:00:00Z")))));
    }

    @Test
    void shellReturnsAggregationFromService() {
        PortalShellService.PortalShell expected = sampleShell();
        when(shellService.shell(eq(MERCHANT_ID), eq(null), eq(null), any())).thenReturn(expected);

        var response = controller.shell(authentication, new MockHttpServletRequest(), null);

        assertThat(response).isSameAs(expected);
        assertThat(response.billing().billedAmount()).isEqualByComparingTo("293.58");
        assertThat(response.recentLeads().get(0).initials()).isEqualTo("JS");
        assertThat(response.notifications().unreadCount()).isEqualTo(1);
    }

    @Test
    void shellWithTimezoneOffsetDelegatesOffset() {
        when(shellService.shell(eq(MERCHANT_ID), eq(null), eq(null), any())).thenReturn(sampleShell());

        var response = controller.shell(authentication, new MockHttpServletRequest(), "-180");

        assertThat(response.greeting().salutation()).isEqualTo("Boa dia");
    }

    @Test
    void shellWithInvalidOffsetThrows400() {
        assertThatThrownBy(() -> controller.shell(authentication, new MockHttpServletRequest(), "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.shell(authentication, new MockHttpServletRequest(), "9000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recentLeadsDelegatesToService() {
        var expected = List.of(new PortalShellService.ShellLead(UUID.randomUUID(),
                "Maria", "MA", Instant.parse("2026-08-15T02:00:00Z")));
        when(shellService.recentLeads(eq(MERCHANT_ID), eq(null), eq(8))).thenReturn(expected);

        var response = controller.recentLeads(authentication, new MockHttpServletRequest());

        assertThat(response).isSameAs(expected);
    }

    @Test
    void scopeProfileIdAndBotIdsArePassedThrough() {
        UUID profileId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        when(scopeResolver.scopeProfileId(any(), any())).thenReturn(Optional.of(profileId));
        when(scopeResolver.scopeBotIds(any(), any())).thenReturn(List.of(botId));
        when(shellService.shell(eq(MERCHANT_ID), eq(profileId), eq(List.of(botId)), any()))
                .thenReturn(sampleShell());
        when(shellService.recentLeads(eq(MERCHANT_ID), eq(List.of(botId)), eq(8)))
                .thenReturn(sampleShell().recentLeads());

        var shell = controller.shell(authentication, new MockHttpServletRequest(), null);
        assertThat(shell).isNotNull();

        var leads = controller.recentLeads(authentication, new MockHttpServletRequest());
        assertThat(leads).hasSize(1);
    }
}
