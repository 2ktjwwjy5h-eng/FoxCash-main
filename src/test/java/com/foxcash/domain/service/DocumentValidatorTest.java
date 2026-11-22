package com.foxcash.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link DocumentValidator} (Spec 18) — CPF/CNPJ com dígitos
 * verificadores corretos são aceitos; inválidos/malformados são rejeitados.
 */
class DocumentValidatorTest {

    @Test
    void acceptsValidCpf() {
        assertThat(DocumentValidator.isValid("52998224725")).isTrue();
        assertThat(DocumentValidator.isValid("111.444.777-35")).isTrue(); // com máscara
    }

    @Test
    void acceptsValidCnpj() {
        assertThat(DocumentValidator.isValid("11222333000181")).isTrue();
        assertThat(DocumentValidator.isValid("11.222.333/0001-81")).isTrue(); // com máscara
    }

    @Test
    void rejectsCpfWithWrongCheckDigit() {
        assertThat(DocumentValidator.isValid("52998224726")).isFalse();
        assertThat(DocumentValidator.isValid("52998224724")).isFalse();
    }

    @Test
    void rejectsRepeatedDigits() {
        assertThat(DocumentValidator.isValid("11111111111")).isFalse(); // CPF repetido
        assertThat(DocumentValidator.isValid("11111111111111")).isFalse(); // CNPJ repetido
    }

    @Test
    void rejectsMalformedOrTooShort() {
        assertThat(DocumentValidator.isValid("123")).isFalse();
        assertThat(DocumentValidator.isValid("123456789")).isFalse(); // CPF incompleto
        assertThat(DocumentValidator.isValid("")).isFalse();
        assertThat(DocumentValidator.isValid(null)).isFalse();
    }

    @Test
    void rejectsCnpjWithWrongCheckDigit() {
        // CNPJ 14 dígitos NÃO-repetido com dígito verificador errado — cobre o
        // path checkDigits do CNPJ (o válido 11222333000181 tem DV ...81).
        assertThat(DocumentValidator.isValid("11222333000182")).isFalse();
        assertThat(DocumentValidator.isValid("11222333000180")).isFalse();
        assertThat(DocumentValidator.isValid("11222333000191")).isFalse();
    }

    @Test
    void acceptsCpfWithCheckDigitRemainderExactlyTwo() {
        // Mutation (ConditionalsBoundary L61: remainder < 2 → <= 2): o CPF
        // 00000013099 tem soma % 11 == 2 no 1º DV → esperado = 11-2 = 9. O
        // mutante (<= 2) calcularia 0 e rejeitaria o documento válido.
        assertThat(DocumentValidator.isValid("00000013099")).isTrue();
    }

    @Test
    void acceptsCpfWithCheckDigitRemainderZero() {
        // Cobre o ramo remainder < 2 (DV = 0): CPF com soma % 11 == 1 → DV 0.
        assertThat(DocumentValidator.isValid("52998224725")).isTrue();
    }
}
