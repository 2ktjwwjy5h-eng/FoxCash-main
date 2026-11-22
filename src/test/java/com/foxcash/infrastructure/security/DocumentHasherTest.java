package com.foxcash.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link DocumentHasher} — auditoria 2026-08-05 (B-A1): o hash de
 * identificadores de baixa entropia (CPF/CNPJ) usa HMAC-SHA256 com pepper do
 * servidor (prefixo {@code h1:}) — não brute-forcável offline; SHA-256 puro
 * permanece apenas para valores de alta entropia (chaves/tokens).
 */
class DocumentHasherTest {

    private static final String DOC = "52998224725";

    @Test
    void hmacIsVersionedDeterministicAndPeppered() {
        String h1 = DocumentHasher.hmac(DOC, "pepper-1");
        String h2 = DocumentHasher.hmac(DOC, "pepper-1");
        String h3 = DocumentHasher.hmac(DOC, "pepper-2");

        assertThat(h1).startsWith("h1:").hasSize(3 + 64);
        assertThat(h1).isEqualTo(h2); // determinístico (mesma pepper)
        assertThat(h1).isNotEqualTo(h3); // pepper diferente → hash diferente
        // Diferente do SHA-256 puro (sem pepper) e não contém o documento.
        assertThat(h1).isNotEqualTo(DocumentHasher.hash(DOC));
        assertThat(h1).doesNotContain(DOC);
    }

    @Test
    void hmacNullReturnsNullAndBlankPepperThrows() {
        assertThat(DocumentHasher.hmac(null, "pepper")).isNull();
        assertThatThrownBy(() -> DocumentHasher.hmac(DOC, "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pepper");
    }
}
