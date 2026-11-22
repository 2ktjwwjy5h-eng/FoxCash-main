package com.foxcash.domain.service;

import com.foxcash.domain.model.withdrawal.WithdrawalDestinationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link WithdrawalDestinationValidator} (Spec 31): validação de
 * formato por tipo (CPF/CNPJ com dígitos verificadores, celular BR, EVP UUID,
 * dados bancários).
 */
class WithdrawalDestinationValidatorTest {

    @Test
    void acceptsValidPixCpf() {
        assertThatCode(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_CPF, "52998224725", null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidPixCnpj() {
        assertThatCode(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_CNPJ, "11222333000181", null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidPixPhone() {
        assertThatCode(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_PHONE, "+5511987654321", null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidPixEvp() {
        assertThatCode(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_EVP, "e6a2a9f0-8f00-4a9a-9b9c-1d2e3f4a5b6c",
                null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidBankAccount() {
        assertThatCode(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.BANK_ACCOUNT, null, "341", "0001", "12345-6"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidCpf() {
        assertThatThrownBy(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_CPF, "123", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCnpjInCpfField() {
        // 14 dígitos num campo PIX_CPF (que exige 11) → inválido.
        assertThatThrownBy(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_CPF, "11222333000181", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidCnpj() {
        assertThatThrownBy(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_CNPJ, "1122233300018", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidPhone() {
        assertThatThrownBy(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_PHONE, "123", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPhoneWithoutCountryCode() {
        assertThatThrownBy(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_PHONE, "11987654321", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEvpThatIsNotUuid() {
        assertThatThrownBy(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_EVP, "not-a-uuid", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBankAccountWithoutAccount() {
        assertThatThrownBy(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.BANK_ACCOUNT, null, "341", "0001", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullType() {
        assertThatThrownBy(() -> WithdrawalDestinationValidator.validate(
                null, "52998224725", null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void rejectsMissingValueForPix(String value) {
        assertThatThrownBy(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_CPF, value, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullValueForPix() {
        assertThatThrownBy(() -> WithdrawalDestinationValidator.validate(
                WithdrawalDestinationType.PIX_CPF, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
