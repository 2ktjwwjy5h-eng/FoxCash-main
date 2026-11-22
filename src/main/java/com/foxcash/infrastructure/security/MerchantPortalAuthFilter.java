package com.foxcash.infrastructure.security;

import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.model.merchant.MerchantSession;
import com.foxcash.domain.model.merchant.MerchantStatus;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.MerchantSessionRepository;
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
 * Autenticacao do lojista no portal por token opaco (Spec 20).
 *
 * <p>Resolve o header {@code Authorization: Bearer <token>}: calcula o hash
 * SHA-256, busca a sessao por {@code token_hash}, valida expiracao/revogacao e
 * o status do lojista ({@code ACTIVE}) → {@code ROLE_MERCHANT_PORTAL} com o
 * {@code merchantId} como principal.
 *
 * <p>Nunca bloqueia sozinho: apenas preenche o {@link SecurityContextHolder}; a
 * autorizacao (401/403) e responsabilidade do {@link SecurityConfig}. Mesmo
 * padrao do {@link MasterAuthFilter} (Spec 13).
 */
public class MerchantPortalAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final MerchantSessionRepository sessionRepository;
    private final MerchantRepository merchantRepository;
    private final SessionTokenGenerator tokenGenerator;
    private final Clock clock;

    public MerchantPortalAuthFilter(MerchantSessionRepository sessionRepository,
                                    MerchantRepository merchantRepository,
                                    SessionTokenGenerator tokenGenerator,
                                    Clock clock) {
        this.sessionRepository = sessionRepository;
        this.merchantRepository = merchantRepository;
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
                        .flatMap(session -> merchantRepository.findById(session.merchantId()))
                        .filter(merchant -> merchant.status() == MerchantStatus.ACTIVE)
                        .ifPresent(merchant -> {
                            // Spec 65 (Fase 1): o lojista ja foi carregado aqui —
                            // o service do charge reusa esta entidade (sem reload).
                            request.setAttribute(
                                    AuthenticatedMerchantResolver.MERCHANT_ATTRIBUTE, merchant);
                            SecurityContextHolder.getContext().setAuthentication(
                                    new UsernamePasswordAuthenticationToken(
                                            merchant.id(), null,
                                            List.of(new SimpleGrantedAuthority("ROLE_MERCHANT_PORTAL"))));
                        });
            }
        }

        chain.doFilter(request, response);
    }
}
