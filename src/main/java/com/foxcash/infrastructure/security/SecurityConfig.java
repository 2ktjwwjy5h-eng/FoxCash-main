package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.port.out.MasterRepository;
import com.foxcash.domain.port.out.MasterSessionRepository;
import com.foxcash.domain.port.out.MerchantApiKeyRepository;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.domain.port.out.MerchantSessionRepository;
import com.foxcash.domain.port.out.SessionTokenGenerator;
import com.foxcash.infrastructure.inbound.rest.dto.ErrorResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.channel.ChannelDecisionManagerImpl;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

/**
 * Seguranca da API (Spec 12 + Spec 13, ADR-006 + ADR-007).
 *
 * <p>Autenticacao por API key no header {@code X-Api-Key}
 * ({@link ApiKeyAuthFilter}) e do perfil master por token opaco
 * ({@code Authorization: Bearer <token>}, {@link MasterAuthFilter}):
 * <ul>
 *   <li>Públicas: {@code POST /api/v1/merchants} (cadastro — onde a chave é
 *       emitida), {@code /webhooks/pix} (autenticado pelo HMAC da Spec 10) e
 *       {@code POST /api/v1/auth/master/login|logout} (Spec 13 — o logout
 *       revoga pelo próprio token apresentado, sem depender da rota);</li>
 *   <li>{@code /api/v1/admin/**} exige {@code ROLE_ADMIN};</li>
 *   <li>{@code /api/v1/master/**} exige {@code ROLE_MASTER} (Spec 13);</li>
 *   <li>demais {@code /api/v1/**} exigem autenticação válida.</li>
 * </ul>
 *
 * <p>Respostas padronizadas {@link ErrorResponse}: 401 {@code unauthorized}
 * (credencial ausente/inválida) e 403 {@code forbidden} (sem a role necessária).
 * Em produção ({@code require-https=true}) o canal HTTPS é obrigatório.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${foxcash.security.require-https:false}")
    private boolean requireHttps;

    @Value("${foxcash.security.admin-api-key:}")
    private String adminApiKey;

    private final MerchantRepository merchantRepository;
    private final MerchantApiKeyRepository merchantApiKeyRepository;
    private final MerchantSessionRepository merchantSessionRepository;
    private final MasterSessionRepository masterSessionRepository;
    private final MasterRepository masterRepository;
    private final SessionTokenGenerator sessionTokenGenerator;
    private final RateLimitFilter rateLimitFilter;
    private final RequestIdFilter requestIdFilter;
    private final RequestBodyInspectionFilter requestBodyInspectionFilter;
    private final AuthenticatedRateLimitFilter authenticatedRateLimitFilter;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final com.foxcash.infrastructure.config.PortalProperties portalProperties;
    private final com.foxcash.infrastructure.config.ConsoleProperties consoleProperties;
    private final ApiKeySecurityProperties apiKeySecurityProperties;

    public SecurityConfig(MerchantRepository merchantRepository,
                          MerchantApiKeyRepository merchantApiKeyRepository,
                          MerchantSessionRepository merchantSessionRepository,
                          MasterSessionRepository masterSessionRepository,
                          MasterRepository masterRepository,
                          SessionTokenGenerator sessionTokenGenerator,
                          RateLimitFilter rateLimitFilter,
                          RequestIdFilter requestIdFilter,
                          RequestBodyInspectionFilter requestBodyInspectionFilter,
                          AuthenticatedRateLimitFilter authenticatedRateLimitFilter,
                          Clock clock,
                          ObjectMapper objectMapper,
                          com.foxcash.infrastructure.config.PortalProperties portalProperties,
                          com.foxcash.infrastructure.config.ConsoleProperties consoleProperties,
                          ApiKeySecurityProperties apiKeySecurityProperties) {
        this.merchantRepository = merchantRepository;
        this.merchantApiKeyRepository = merchantApiKeyRepository;
        this.merchantSessionRepository = merchantSessionRepository;
        this.masterSessionRepository = masterSessionRepository;
        this.masterRepository = masterRepository;
        this.sessionTokenGenerator = sessionTokenGenerator;
        this.rateLimitFilter = rateLimitFilter;
        this.requestIdFilter = requestIdFilter;
        this.requestBodyInspectionFilter = requestBodyInspectionFilter;
        this.authenticatedRateLimitFilter = authenticatedRateLimitFilter;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.portalProperties = portalProperties;
        this.consoleProperties = consoleProperties;
        this.apiKeySecurityProperties = apiKeySecurityProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        ApiKeyAuthFilter apiKeyAuthFilter = new ApiKeyAuthFilter(
                merchantRepository, merchantApiKeyRepository, adminApiKey, clock, objectMapper,
                apiKeySecurityProperties);
        MasterAuthFilter masterAuthFilter = new MasterAuthFilter(
                masterSessionRepository, masterRepository, sessionTokenGenerator, clock);
        MerchantPortalAuthFilter merchantPortalAuthFilter = new MerchantPortalAuthFilter(
                merchantSessionRepository, merchantRepository, sessionTokenGenerator, clock);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Cadastro de lojista é público: é onde a chave de API é emitida.
                        .requestMatchers(HttpMethod.POST, "/api/v1/merchants").permitAll()
                        // Webhooks: autenticados pela assinatura HMAC (WebhookSignatureFilter).
                        .requestMatchers("/webhooks/pix", "/webhooks/payouts").permitAll()
                        // Spec 74: webhook público de updates do Telegram — segredo na URL
                        // (/webhooks/telegram/{id}/{secret}) + header secret_token validados
                        // pelo TelegramWebhookController (404 silencioso em segredo inválido).
                        .requestMatchers("/webhooks/telegram/**").permitAll()
                        // Login/logout do master (Spec 13): login valida usuário/senha;
                        // logout revoga pelo próprio token apresentado (sem depender da rota).
                        .requestMatchers("/api/v1/auth/master/login", "/api/v1/auth/master/logout").permitAll()
                        // SEC-25 (revisão de segurança 2026-08-11): troca de senha do
                        // master EXIGE ROLE_MASTER (sessão ativa) — regra explícita
                        // antes do permitAll acima não se aplica (paths distintos) e o
                        // matcher genérico /api/v1/** (ADMIN/MASTER/MERCHANT) não
                        // alcança a intenção: só o master autenticado troca a própria.
                        .requestMatchers("/api/v1/auth/master/change-password").hasRole("MASTER")
                        // Login/logout do lojista no portal (Spec 20) e recuperação
                        // de senha (Spec 40 — forgot/reset são públicos: o token
                        // one-time é a credencial de posse) — mesmo padrão do master.
                        .requestMatchers("/api/v1/merchant-auth/login", "/api/v1/merchant-auth/logout",
                                "/api/v1/merchant-auth/forgot-password",
                                "/api/v1/merchant-auth/reset-password").permitAll()
                        // Observabilidade (2026-08-07): /actuator/health (liveness/
                        // readiness) é público — o orquestrador (K8s/Railway/CI) precisa
                        // sondar sem credencial. Os demais endpoints do actuator
                        // (prometheus/info/metrics) exigem ROLE_ADMIN: dados de métrica
                        // não são públicos.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Spec 91 (E2E): controle de simulação dos mocks Pix —
                        // o controller é @Profile("dev") (SÓ existe em dev); em
                        // produção este matcher não expõe NADA (404 sem bean).
                        .requestMatchers("/internal/psp-mock/**").permitAll()
                        // Spec 46: com a SPA servida (foxcash.portal.serve-frontend=true),
                        // as rotas do frontend e os assets são públicos — o guard de
                        // auth roda no cliente (redirect /login). Sem o flag, qualquer
                        // rota fora da API cai no anyRequest().authenticated() (401).
                        .requestMatchers(spaPublicPaths()).permitAll()
                        // Spec 63 (Fase C): a SPA precisa da SITE KEY pública do
                        // Turnstile antes do login — endpoint público (sem credencial;
                        // só expõe a site key, que vai para o browser de qualquer forma).
                        .requestMatchers("/api/v1/portal/turnstile-config").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/master/**").hasRole("MASTER")
                        .requestMatchers("/api/v1/portal/**").hasRole("MERCHANT_PORTAL")
                        // Saque do lojista (Spec 14/32): credencial valida = chave de API
                        // OU sessao do portal (a tela de saque usa o Bearer do portal).
                        .requestMatchers("/api/v1/merchants/*/withdrawals/**")
                                .hasAnyRole("ADMIN", "MASTER", "MERCHANT", "MERCHANT_PORTAL")
                        // Rotas de API restantes (merchants/transactions/foxcash/billing/...):
                        // exigem role de API — a sessão do PORTAL (ROLE_MERCHANT_PORTAL)
                        // NÃO acessa a API REST (fix auditoria de segurança 2026-08-04:
                        // antes o anyRequest().authenticated() aceitava o Bearer do
                        // portal em /api/v1/merchants/** e /api/v1/foxcash/pix/** etc.).
                        .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "MASTER", "MERCHANT")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    ErrorResponse.of("unauthorized", "API key ou token ausente/invalido")));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    ErrorResponse.of("forbidden", "Acesso negado")));
                        }))
                .addFilterBefore(requestIdFilter, UsernamePasswordAuthenticationFilter.class)
                // Spec 63 (camada app): inspecao de corpo ANTES do rate limit e da
                // autenticacao — payload de ataque (SQLi/XSS/path traversal) e
                // barrado com 403 body_inspection_failed sem gastar CPU/DB de auth.
                // O body ja chega cacheado (RequestBodySizeFilter, precedencia maxima).
                .addFilterBefore(requestBodyInspectionFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(masterAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(merchantPortalAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // Spec 58: rate limit por PRINCIPAL autenticado — DEVE rodar DEPOIS
                // dos filtros de auth (apiKeyAuthFilter é o último da ordem de
                // execução) para o SecurityContext já conter o principal. Sem isso
                // o limite nunca dispara (principal ainda anônimo).
                .addFilterAfter(authenticatedRateLimitFilter, ApiKeyAuthFilter.class);

        // Headers de segurança (auditoria 2026-08-05/B-A3): além dos defaults do
        // Spring Security (nosniff, frame DENY, no-store), API pura não renderiza
        // HTML — CSP restrito (default-src 'none'), Referrer-Policy e
        // Permissions-Policy. HSTS apenas quando HTTPS é obrigatório (require-https).
        // Spec 46: com a SPA servida (serve-frontend=true), a CSP muda para
        // permitir 'self' + estilos inline (Vite) + QR images data: (design.md §4).
        // Spec 46/71: com a SPA servida (portal OU console — mesmo origin, sem
        // CORS), a CSP muda para permitir 'self' + estilos inline (Vite) + QR
        // images data: (design.md §4 da Spec 46; D4.2 da Spec 71 — política
        // compartilhada; inerte quando o console não carrega o widget Turnstile).
        http.headers(headers -> {
            headers
                    .contentSecurityPolicy(csp -> csp.policyDirectives(
                            portalProperties.isServeFrontend() || consoleProperties.isServeFrontend()
                                    // Spec 63 (Fase C): o widget Turnstile carrega script +
                                    // iframe de challenges.cloudflare.com (login/cadastro).
                                    ? "default-src 'self'; script-src 'self' https://challenges.cloudflare.com; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' https://challenges.cloudflare.com; frame-src https://challenges.cloudflare.com; frame-ancestors 'none'"
                                    : "default-src 'none'; frame-ancestors 'none'"))
                    .referrerPolicy(referrer -> referrer.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                    .permissionsPolicy(permissions -> permissions.policy(
                            "geolocation=(), microphone=(), camera=()"));
            if (requireHttps) {
                // HSTS (homologação 2026-08-05): com HTTPS obrigatório, o navegador
                // deve ser forçado a usar apenas HTTPS (1 ano, subdomínios) —
                // mitiga downgrade/SSL-strip na primeira visita.
                headers.httpStrictTransportSecurity(hsts ->
                        hsts.includeSubDomains(true).maxAgeInSeconds(31536000));
            }
        });

        // Spec 46: CORS apenas para origins explicitamente configurados
        // (foxcash.portal.cors-allowed-origins) — consumo externo da API do
        // portal. Vazio = CORS desabilitado (SPA servida no mesmo origin).
        if (!portalProperties.getCorsAllowedOrigins().isEmpty()) {
            http.cors(cors -> cors.configurationSource(request -> {
                var corsConfig = new org.springframework.web.cors.CorsConfiguration();
                corsConfig.setAllowedOrigins(portalProperties.getCorsAllowedOrigins());
                corsConfig.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                corsConfig.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type"));
                corsConfig.setAllowCredentials(true);
                corsConfig.setMaxAge(3600L);
                return corsConfig;
            }));
        }

        if (requireHttps) {
            http.requiresChannel(channel -> channel
                    // Healthcheck do orquestrador (Railway) sonda o container via
                    // HTTP puro (sem X-Forwarded-Proto). O requiresSecure() abaixo
                    // responderia 302 e o Railway marcaria o deploy como FAILED
                    // ("replicas never became healthy" — verificado 2026-08-08).
                    // Eximir /actuator/health/** do redirect de canal
                    // (ANY_CHANNEL = nenhum ChannelProcessor decide). HSTS e o
                    // redirect continuam valendo para o tráfego público via edge.
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                            .requires(ChannelDecisionManagerImpl.ANY_CHANNEL)
                    .anyRequest().requiresSecure());
        }
        return http.build();
    }

    /**
     * Spec 46/71: rotas publicas das SPAs (index.html + assets + telas do
     * react-router). Com {@code foxcash.portal.serve-frontend=false} E
     * {@code foxcash.console.serve-frontend=false} retorna array vazio —
     * {@code requestMatchers()} vazio não casa NENHUM request
     * (OrRequestMatcher), então nada extra é exposto; qualquer rota fora da API
     * cai no {@code anyRequest().authenticated()} (401).
     */
    private String[] spaPublicPaths() {
        boolean anySpa = portalProperties.isServeFrontend() || consoleProperties.isServeFrontend();
        if (!anySpa) {
            return new String[0];
        }
        java.util.List<String> paths = new java.util.ArrayList<>(java.util.List.of(
                "/", "/index.html", "/assets/**",
                "/login", "/register", "/forgot-password", "/reset-password",
                "/dashboard", "/bots", "/bots/creator/telegram", "/bots/creator/discord", "/wallet", "/charges", "/sales",
                "/destinations", "/withdraw", "/webhooks", "/credentials",
                // Spec 80: rotas do broadcast (editor + lista) — o full reload
                // (F5) de /broadcasts* precisa servir o index.html da SPA (o
                // guard client-side redireciona para /login sem sessão; sem a
                // whitelist o navegador receberia 401 JSON da API).
                "/broadcasts", "/broadcasts/*",
                // Spec 82: painel de tracking (rota client-side do react-router).
                "/tracking", "/tracking/*",
                // Spec 84: painel de clientes/CRM (rota client-side do react-router).
                "/crm", "/crm/*",
                // Spec 108: visão de gateways do lojista (rota client-side do react-router).
                "/gateways",
                // Spec 119: Comunidade (rota client-side do react-router).
                "/community"));
        if (consoleProperties.isServeFrontend()) {
            // Spec 71: rotas do Master Console + assets do build (base /console/).
            // /console/fonts/** (2026-08-12): fontes auto-hospedadas (Geist Sans +
            // Plus Jakarta Sans) — o CSS do SPA carrega as woff2 sem header de
            // Authorization (401 sem a whitelist).
            paths.addAll(java.util.List.of(
                    "/console", "/console/", "/console/index.html", "/console/assets/**",
                    "/console/fonts/**",
                    "/console/login", "/console/dashboard", "/console/transactions",
                    "/console/merchants", "/console/merchants/*",
                    // Spec 100: config fina por lojista (rota client-side — full
                    // reload/F5 deve servir o index.html da SPA, lição Spec 80/84).
                    "/console/merchants/*/config",
                    "/console/withdrawals",
                    "/console/financial", "/console/receivables", "/console/audit",
                    "/console/activity", "/console/reconciliation", "/console/lookup",
                    "/console/bots",
                    // Spec 89: auditoria de broadcasts (rota client-side do react-router).
                    "/console/broadcasts",
                    // Spec 127: trace de transação (rota client-side do react-router).
                    "/console/trace",
                    "/console/security", "/console/profiles"));
        }
        return paths.toArray(new String[0]);
    }
}
