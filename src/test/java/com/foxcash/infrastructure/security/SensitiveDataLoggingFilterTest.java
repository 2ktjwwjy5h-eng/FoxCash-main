package com.foxcash.infrastructure.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Testes da sanitizacao de dados sensiveis nos logs (auditoria 2026-08-05/B7):
 * alem do CPF/CNPJ, campos sensiveis em JSON (apiKey, password, token, secret,
 * document, pixKey...) tem o valor mascarado — nivel DEBUG nunca loga segredo.
 */
class SensitiveDataLoggingFilterTest {

    @Test
    void masksCpfAndCnpj() {
        assertThat(SensitiveDataLoggingFilter.sanitize("cpf=529.982.247-25"))
                .isEqualTo("cpf=***");
        assertThat(SensitiveDataLoggingFilter.sanitize("cnpj=11.222.333/0001-81"))
                .isEqualTo("cnpj=***");
    }

    @Test
    void masksSensitiveJsonFields() {
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"apiKey\":\"mch_abcdef123456\"}"))
                .isEqualTo("{\"apiKey\":\"***\"}");
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"password\":\"segredo\"}"))
                .isEqualTo("{\"password\":\"***\"}");
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"token\":\"mst_abc\"}"))
                .isEqualTo("{\"token\":\"***\"}");
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"secret\":\"0123456789abcdef\"}"))
                .isEqualTo("{\"secret\":\"***\"}");
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"document\":\"52998224725\"}"))
                .isEqualTo("{\"document\":\"***\"}");
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"pixKey\":\"pix@loja.com\"}"))
                .isEqualTo("{\"pixKey\":\"***\"}");
        // Spec 39: e-mail (identificador de login) nunca aparece em claro nos logs.
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"email\":\"loja@example.com\"}"))
                .isEqualTo("{\"email\":\"***\"}");
        // Spec 40: token de recuperação de senha mascarado (camelCase e snake_case).
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"resetToken\":\"rst_abc123\"}"))
                .isEqualTo("{\"resetToken\":\"***\"}");
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"reset_token\":\"rst_abc123\"}"))
                .isEqualTo("{\"reset_token\":\"***\"}");
        // CamelCase e underscore sao cobertos; CASE_INSENSITIVE.
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"API_KEY\":\"x\"}"))
                .isEqualTo("{\"API_KEY\":\"***\"}");
    }

    @Test
    void doesNotMaskUnrelatedContent() {
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"name\":\"Loja A\"}"))
                .isEqualTo("{\"name\":\"Loja A\"}");
        // Jackson normaliza números (150.00 → 150.0) na re-serialização — o
        // conteúdo não sensível é preservado (apenas o formato do log muda).
        assertThat(SensitiveDataLoggingFilter.sanitize("{\"amount\":150.00}"))
                .isEqualTo("{\"amount\":150.0}");
    }

    @Test
    void masksValueContainingQuote() {
        // Auditoria 2026-08-05 (B-A4): a regex antiga quebrava com " dentro do
        // valor (senha com aspas vazaria o restante) — agora o JSON é parseado.
        String sanitized = SensitiveDataLoggingFilter.sanitize(
                "{\"password\":\"seg\\\"redo\",\"name\":\"Loja\"}");
        assertThat(sanitized).contains("\"password\":\"***\"");
        assertThat(sanitized).doesNotContain("seg");
        assertThat(sanitized).contains("\"name\":\"Loja\"");
    }

    @Test
    void masksSensitiveKeysNestedAndCamelCase() {
        // Chaves sensíveis aninhadas e em camelCase também são mascaradas.
        String sanitized = SensitiveDataLoggingFilter.sanitize(
                "{\"credential\":{\"temporaryPassword\":\"mst_abc\",\"email\":\"x@y.z\"},\"name\":\"A\"}");
        assertThat(sanitized).contains("\"temporaryPassword\":\"***\"");
        assertThat(sanitized).contains("\"email\":\"***\"");
        assertThat(sanitized).contains("\"name\":\"A\"");
    }

    @Test
    void sanitizeBlankOrNullReturnsInputAsIs() {
        // L117: o return antecipado (blank/null) não pode virar "" — o log
        // continuaria com o corpo original em claro.
        assertThat(SensitiveDataLoggingFilter.sanitize(null)).isNull();
        assertThat(SensitiveDataLoggingFilter.sanitize("   ")).isEqualTo("   ");
    }

    @Test
    void sanitizePreservesScalarJsonValues() {
        // L139: mask() só transforma Map/Collection — escalares passam intactos
        // (mutante NullReturnVals converteria 42/[1,2,3] em "null").
        assertThat(SensitiveDataLoggingFilter.sanitize("42")).isEqualTo("42");
        assertThat(SensitiveDataLoggingFilter.sanitize("[1,2,3]")).isEqualTo("[1,2,3]");
    }

    @Test
    void disabledFilterPassesThroughWithoutWrapping() throws Exception {
        SensitiveDataLoggingFilter filter = new SensitiveDataLoggingFilter(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        // Desabilitado: o request ORIGINAL segue (sem wrapper, sem copyBodyToResponse).
        verify(chain).doFilter(same(request), same(response));
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void enabledFilterLogsSanitizedBodiesAndCopiesResponse() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SensitiveDataLoggingFilter.class);
        Level original = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SensitiveDataLoggingFilter filter = new SensitiveDataLoggingFilter(true);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setContent("{\"password\":\"segredo\"}".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);
            // O downstream consome o body do request e grava o body da resposta
            // (necessário para o ContentCachingWrapper cachear ambos).
            doAnswer(inv -> {
                HttpServletRequest req = inv.getArgument(0);
                HttpServletResponse resp = inv.getArgument(1);
                new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                resp.getWriter().write("{\"token\":\"resp-secret\"}");
                return null;
            }).when(chain).doFilter(any(), any());

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(any(ContentCachingRequestWrapper.class),
                    any(ContentCachingResponseWrapper.class));
            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            // L95/L99/L108: bodies presentes → log sanitizado (mutantes que
            // devolvessem "" ou negassem o isBlank sumiriam com a linha).
            assertThat(logs).contains("Request body (sanitizado)")
                    .contains("\"password\":\"***\"");
            assertThat(logs).contains("Response body (sanitizado)")
                    .contains("\"token\":\"***\"");
            // L104: copyBodyToResponse devolve o corpo ao cliente (o wrapper consome o stream).
            assertThat(response.getContentAsString()).isEqualTo("{\"token\":\"resp-secret\"}");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(original);
        }
    }
}
