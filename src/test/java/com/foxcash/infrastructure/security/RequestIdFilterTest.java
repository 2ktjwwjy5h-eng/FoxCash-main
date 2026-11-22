package com.foxcash.infrastructure.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Testes do {@link RequestIdFilter} — auditoria 2026-08-05 (B-03): o header
 * {@code X-Request-Id} enviado pelo cliente é validado antes de ser ecoado.
 * Valor fora do padrão (injeção de header via CR/LF, caracteres de controle,
 * tamanho excessivo) é substituído por um UUID gerado no servidor.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private MockHttpServletResponse run(String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader(RequestIdFilter.HEADER, headerValue);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response;
    }

    @Test
    void echoesSafeClientHeader() throws Exception {
        MockHttpServletResponse response = run("req-123_abc.def:456");

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("req-123_abc.def:456");
    }

    @Test
    void generatesUuidWhenHeaderMissing() throws Exception {
        MockHttpServletResponse response = run(null);

        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void replacesHeaderInjectionAttempt() throws Exception {
        // CR/LF em header → injeção de cabeçalho; o filtro gera UUID em vez de ecoar.
        MockHttpServletResponse response = run("ok\r\nX-Evil: 1");

        String echoed = response.getHeader(RequestIdFilter.HEADER);
        assertThat(echoed).doesNotContain("X-Evil").doesNotContain("\r").doesNotContain("\n");
        assertThat(echoed).contains("-");
    }

    @Test
    void replacesUnsafeAndOversizedHeaders() throws Exception {
        // Caracteres de controle/scripts → substituídos por UUID.
        assertThat(run("<script>alert(1)</script>").getHeader(RequestIdFilter.HEADER))
                .doesNotContain("<script>");
        // Tamanho acima de 128 → substituído por UUID.
        assertThat(run("a".repeat(200)).getHeader(RequestIdFilter.HEADER)).hasSize(36);
    }
}
