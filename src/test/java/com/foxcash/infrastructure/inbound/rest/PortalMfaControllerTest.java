package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.port.in.MerchantMfaUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MfaCodeRequest;
import com.foxcash.infrastructure.inbound.rest.dto.MfaDeactivateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link PortalMfaController} (Spec 94) — espelho do
 * {@code MasterMfaControllerTest} (Spec 44) sem o step-up (fora de escopo).
 */
class PortalMfaControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    private final MerchantMfaUseCase mfa = mock(MerchantMfaUseCase.class);
    private final Authentication authentication = mock(Authentication.class);
    private PortalMfaController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalMfaController(mfa);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    @Test
    void activateReturnsUriAndManualEntryKeyFromPrincipal() {
        when(mfa.activate(MERCHANT_ID)).thenReturn(
                new MerchantMfaUseCase.ActivateResult(
                        "otpauth://totp/FoxCash:Loja?secret=ABC", "ABC"));

        ResponseEntity<Map<String, String>> response = controller.activate(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("otpauthUri",
                        "otpauth://totp/FoxCash:Loja?secret=ABC")
                .containsEntry("manualEntryKey", "ABC");
        verify(mfa).activate(MERCHANT_ID);
    }

    @Test
    void confirmReturnsRecoveryCodesAndUsesRequestCode() {
        MfaCodeRequest request = new MfaCodeRequest("123456");
        when(mfa.confirm(MERCHANT_ID, "123456")).thenReturn(
                new MerchantMfaUseCase.ConfirmResult(List.of("mrc_a", "mrc_b")));

        ResponseEntity<Map<String, Object>> response = controller.confirm(authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("recoveryCodes", List.of("mrc_a", "mrc_b"));
        verify(mfa).confirm(MERCHANT_ID, "123456");
    }

    @Test
    void deactivateReturnsNoContentAndUsesRequestCodeAndPassword() {
        MfaDeactivateRequest request = new MfaDeactivateRequest("123456", "senha-certa");

        ResponseEntity<Void> response = controller.deactivate(authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(mfa).deactivate(MERCHANT_ID, "123456", "senha-certa");
    }

    @Test
    void regenerateRecoveryCodesReturnsNewCodesAndUsesRequestCode() {
        MfaCodeRequest request = new MfaCodeRequest("123456");
        when(mfa.regenerateRecoveryCodes(MERCHANT_ID, "123456"))
                .thenReturn(List.of("mrc_novo1", "mrc_novo2"));

        ResponseEntity<Map<String, Object>> response =
                controller.regenerateRecoveryCodes(authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("recoveryCodes", List.of("mrc_novo1", "mrc_novo2"));
        verify(mfa).regenerateRecoveryCodes(MERCHANT_ID, "123456");
    }

    @Test
    void invalidMfaCodePropagatesForUniform401() {
        // O controller não traduz a exceção — o GlobalExceptionHandler devolve o
        // 401 uniforme (sem oráculo). Aqui garante-se a propagação intacta.
        when(mfa.confirm(MERCHANT_ID, "000000"))
                .thenThrow(new InvalidMfaCodeException());

        assertThatThrownBy(() -> controller.confirm(authentication, new MfaCodeRequest("000000")))
                .isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    void usesAuthenticatedMerchantIdFromPrincipalNotFromRequest() {
        // O portal NÃO gerencia TOTP de terceiros: o merchantId vem do principal
        // (o token autenticado), nunca de um campo do corpo — evita escalada.
        MerchantMfaUseCase.ActivateResult result = new MerchantMfaUseCase.ActivateResult("uri", "secret");
        when(mfa.activate(MERCHANT_ID)).thenReturn(result);

        controller.activate(authentication);

        verify(mfa).activate(MERCHANT_ID);
    }
}
