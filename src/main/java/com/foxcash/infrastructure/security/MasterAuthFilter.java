package com.foxcash.infrastructure.security;

import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterSession;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.MasterSessionRepository;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

/**
 * Autenticacao do perfil master por token opaco (Spec 13, ADR-007).
 *
 * <p>Resolve o header {@code Authorization: Bearer <token>}: calcula o hash
 * SHA-256, busca a sessao por {@code token_hash}, valida expiracao/revogacao e
 * o status do master ({@code ACTIVE}) → {@code ROLE_MASTER} com o
 * {@code masterId} como principal.
 *
 * <p>Nunca bloqueia sozinho: apenas preenche o {@link SecurityContextHolder}; a
 * autorizacao (401/403) e responsabilidade do {@link SecurityConfig}. Caminhos
 * sem header Bearer seguem sem autenticacao de master (podendo ainda ser
 * autenticados por API key — {@link ApiKeyAuthFilter}).
 */
public class MasterAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final MasterSessionRepository sessionRepository;
    private final MasterRepository masterRepository;
    private final SessionTokenGenerator tokenGenerator;
    private final Clock clock;

    public MasterAuthFilter(MasterSessionRepository sessionRepository,
                            MasterRepository masterRepository,
                            SessionTokenGenerator tokenGenerator,
                            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.masterRepository = masterRepository;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");

        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String rawToken = authorization.substring(BEARER_PREFIX.length()).trim();
            if (!rawToken.isBlank()) {
                sessionRepository.findByTokenHash(tokenGenerator.hash(rawToken))
                        .filter(session -> session.isValid(clock.instant()))
                        .flatMap(session -> masterRepository.findById(session.masterId()))
                        .filter(Master::isActive)
                        .ifPresent(master -> SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                        master.id(), null,
                                        List.of(new SimpleGrantedAuthority("ROLE_MASTER")))));
            }
        }

        chain.doFilter(request, response);
    }
}
