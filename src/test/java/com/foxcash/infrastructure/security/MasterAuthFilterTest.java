package com.foxcash.infrastructure.security;

import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterSession;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.MasterSessionRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterAuthFilter} (Spec 13): resolve {@code Authorization:
 * Bearer <token>} → sessão válida (não expirada/revogada, master ACTIVE) →
 * {@code ROLE_MASTER} com masterId no principal; sem token válido → nenhuma
 * autenticação (as rotas decidem 401/403).
 */
class MasterAuthFilterTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final String RAW_TOKEN = "mst_0123456789abcdef0123456789abcdef";

    private final MasterSessionRepository sessionRepository = mock(MasterSessionRepository.class);
    private final MasterRepository masterRepository = mock(MasterRepository.class);
    private final SessionTokenGenerator tokenGenerator = new SessionTokenGeneratorImpl();

    private MasterAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MasterAuthFilter(
                sessionRepository, masterRepository, tokenGenerator,
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
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

    private void stubValidSession() {
        String hash = tokenGenerator.hash(RAW_TOKEN);
        MasterSession session = MasterSession.issue(MASTER_ID, hash, NOW.minusSeconds(1), NOW.plus(Duration.ofHours(8)));
        when(sessionRepository.findByTokenHash(hash)).thenReturn(Optional.of(session));
        when(masterRepository.findById(MASTER_ID)).thenReturn(Optional.of(
                Master.restore(MASTER_ID, "master", "hash", "Master Principal", true, NOW)));
    }

    @Test
    void validBearerTokenGrantsMasterRoleWithMasterIdPrincipal() throws Exception {
        stubValidSession();

        Authentication auth = authenticate("/api/v1/master/dashboard", "Bearer " + RAW_TOKEN);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_MASTER");
        // masterId (UUID) como principal — pronto para futuras checagens de posse.
        assertThat(auth.getPrincipal()).isEqualTo(MASTER_ID);
    }

    @Test
    void expiredOrRevokedTokenIsNotAuthenticated() throws Exception {
        String hash = tokenGenerator.hash(RAW_TOKEN);
        // Sessão expirada (expires_at no passado).
        MasterSession expired = MasterSession.issue(MASTER_ID, hash, NOW.minus(Duration.ofHours(2)), NOW.minusSeconds(1));
        when(sessionRepository.findByTokenHash(hash)).thenReturn(Optional.of(expired));

        Authentication auth = authenticate("/api/v1/master/dashboard", "Bearer " + RAW_TOKEN);

        assertThat(auth).isNull();
    }

    @Test
    void missingOrNonBearerAuthorizationIsNotAuthenticated() throws Exception {
        // Sem header Authorization.
        assertThat(authenticate("/api/v1/master/dashboard", null)).isNull();

        // Header que não é Bearer (ex.: Basic).
        assertThat(authenticate("/api/v1/master/dashboard", "Basic dXNlcjpzZW5oYQ==")).isNull();

        // Bearer sem token (apenas o prefixo).
        assertThat(authenticate("/api/v1/master/dashboard", "Bearer ")).isNull();

        // Token desconhecido: nenhuma sessão encontrada.
        when(sessionRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThat(authenticate("/api/v1/master/dashboard", "Bearer mst_ffffffffffffffffffffffffffffffff")).isNull();
    }
}
