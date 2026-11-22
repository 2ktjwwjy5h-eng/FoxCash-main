package com.foxcash.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link RequestBodySizeFilter} (auditoria 2026-08-05/M-01): teto
 * global do corpo da requisicao — acima do limite → 413 {@code payload_too_large}
 * sem alocar o corpo inteiro; corpo valido reexposto via
 * {@link CachedBodyHttpServletRequest} para os filtros a jusante.
 */
class RequestBodySizeFilterTest {

    private RequestBodySizeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestBodySizeFilter(new ObjectMapper());
    }

    @Test
    void rejectsBodyAboveLimitWith413() throws Exception {
        MockHttpServletRequest request = post("/api/v1/merchants",
                new byte[RequestBodySizeFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        assertThat(response.getContentAsString()).contains("payload_too_large");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsDeclaredContentLengthAboveLimitWithoutReadingBody() throws Exception {
        // Content-Length declarado acima do teto SEM corpo real — fast path:
        // o filtro rejeita pelo header sem ler o stream (sem setContent, o corpo
        // vazio passaria se a leitura ocorresse — o override prova o fast path).
        MockHttpServletRequest base = new MockHttpServletRequest("POST", "/api/v1/merchants");
        HttpServletRequestWrapper declared = new HttpServletRequestWrapper(base) {
            @Override
            public long getContentLengthLong() {
                return RequestBodySizeFilter.MAX_BODY_BYTES + 10L;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(declared, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void acceptsBodyWithinLimitAndReusesItDownstream() throws Exception {
        String body = "{\"name\":\"Loja\",\"email\":\"loja@example.com\",\"password\":\"Senha-Segura-123\"}";
        MockHttpServletRequest request = post("/api/v1/merchants", body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(body);
    }

    @Test
    void acceptsBodyExactlyAtLimit() throws Exception {
        MockHttpServletRequest request = post("/api/v1/merchants",
                new byte[RequestBodySizeFilter.MAX_BODY_BYTES]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isInstanceOf(CachedBodyHttpServletRequest.class);
    }

    @Test
    void skipsMethodsWithoutBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/merchants/abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void skipsPostWithEmptyBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchants");
        // Corpo vazio real (Content-Length: 0) — sem corpo a limitar.
        request.setContent(new byte[0]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    private static MockHttpServletRequest post(String path, byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContent(content);
        return request;
    }
}
