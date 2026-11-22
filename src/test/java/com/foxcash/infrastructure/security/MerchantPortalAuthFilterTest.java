package com.foxcash.infrastructure.security;

import com.foxcash.domain.model.Money;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantSession;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.model.pricing.PricingPlan;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.MerchantSessionRepository;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MerchantPortalAuthFilter} (Spec 20): resolve
 * {@code Authorization: Bearer <token>} → sessão válida (não
 * expirada/revogada, lojista ACTIVE) → {@code ROLE_MERCHANT_PORTAL} com
 * merchantId no principal; sem token válido → nenhuma autenticação.
 */
class MerchantPortalAuthFilterTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final String RAW_TOKEN = "mch-foxcash-token";
    private static final PricingPlan PLAN = new PricingPlan(Money.of("0.50"), Money.of("69.95"));

    private final MerchantSessionRepository sessionRepository = mock(MerchantSessionRepository.class);
    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final SessionTokenGenerator tokenGenerator = new SessionTokenGeneratorImpl();

    private MerchantPortalAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MerchantPortalAuthFilter(
                sessionRepository, merchantRepository, tokenGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private Authentication authenticate(String path, String authorization) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(path, authorization), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private Merchant merchant(MerchantStatus status) {
        return Merchant.restore(MERCHANT_ID, "Loja A LTDA", null, "52998224725",
                "hash", status, PLAN, Money.of("0.00"), false, NOW);
    }

    private void stubValidSession() {
        String hash = tokenGenerator.hash(RAW_TOKEN);
        MerchantSession session = MerchantSession.issue(MERCHANT_ID, hash, NOW.minusSeconds(1), NOW.plus(Duration.ofHours(8)));
        when(sessionRepository.findByTokenHash(hash)).thenReturn(Optional.of(session));
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.ACTIVE)));
    }

    @Test
    void validBearerTokenGrantsMerchantPortalRoleWithMerchantIdPrincipal() throws Exception {
        stubValidSession();

        Authentication auth = authenticate("/api/v1/portal/me", "Bearer " + RAW_TOKEN);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_MERCHANT_PORTAL");
        assertThat(auth.getPrincipal()).isEqualTo(MERCHANT_ID);
    }

    @Test
    void expiredOrRevokedTokenIsNotAuthenticated() throws Exception {
        String hash = tokenGenerator.hash(RAW_TOKEN);
        MerchantSession expired = MerchantSession.issue(MERCHANT_ID, hash, NOW.minus(Duration.ofHours(2)), NOW.minusSeconds(1));
        when(sessionRepository.findByTokenHash(hash)).thenReturn(Optional.of(expired));

        Authentication auth = authenticate("/api/v1/portal/me", "Bearer " + RAW_TOKEN);

        assertThat(auth).isNull();
    }

    @Test
    void suspendedMerchantIsNotAuthenticated() throws Exception {
        String hash = tokenGenerator.hash(RAW_TOKEN);
        MerchantSession session = MerchantSession.issue(MERCHANT_ID, hash, NOW.minusSeconds(1), NOW.plus(Duration.ofHours(8)));
        when(sessionRepository.findByTokenHash(hash)).thenReturn(Optional.of(session));
        when(merchantRepository.findById(MERCHANT_ID)).thenReturn(Optional.of(merchant(MerchantStatus.SUSPENDED)));

        Authentication auth = authenticate("/api/v1/portal/me", "Bearer " + RAW_TOKEN);

        assertThat(auth).isNull();
    }

    @Test
    void missingOrNonBearerAuthorizationIsNotAuthenticated() throws Exception {
        assertThat(authenticate("/api/v1/portal/me", null)).isNull();
        assertThat(authenticate("/api/v1/portal/me", "Basic dXNlcjpzZW5oYQ==")).isNull();
        assertThat(authenticate("/api/v1/portal/me", "Bearer ")).isNull();

        when(sessionRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThat(authenticate("/api/v1/portal/me", "Bearer mst_ffffffffffffffffffffffffffffffff")).isNull();
    }
}
