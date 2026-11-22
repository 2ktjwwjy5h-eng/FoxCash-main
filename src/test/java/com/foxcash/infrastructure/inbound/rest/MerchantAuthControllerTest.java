package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.MerchantPortalAuthUseCase;
import com.foxcash.domain.port.in.PasswordResetUseCase;
import com.foxcash.infrastructure.config.AuthProperties;
import com.foxcash.infrastructure.inbound.rest.dto.ForgotPasswordRequest;
import com.foxcash.infrastructure.inbound.rest.dto.ForgotPasswordResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ResetPasswordRequest;
import com.foxcash.infrastructure.security.TurnstileVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes das rotas públicas de recuperação de senha (Spec 40) — em especial o
 * contrato anti-enumeração: {@code resetToken} só é devolvido em dev
 * ({@code expose-reset-token=true}) e com e-mail existente; em prod a resposta
 * é sempre 204.
 */
class MerchantAuthControllerTest {

    private final MerchantPortalAuthUseCase portalAuth = mock(MerchantPortalAuthUseCase.class);
    private final PasswordResetUseCase passwordReset = mock(PasswordResetUseCase.class);
    private final AuthProperties authProperties = new AuthProperties();
    private final TurnstileVerifier turnstileVerifier = mock(TurnstileVerifier.class);

    private MerchantAuthController controller() {
        return new MerchantAuthController(portalAuth, passwordReset, authProperties, turnstileVerifier);
    }

    private ForgotPasswordRequest forgot(String email) {
        return new ForgotPasswordRequest(email);
    }

    @Test
    void forgotInDevWithExistingEmailReturnsResetToken() {
        authProperties.setExposeResetToken(true);
        when(passwordReset.forgot(any()))
                .thenReturn(PasswordResetUseCase.ForgotResult.of("rst_abc123"));

        ResponseEntity<?> response = controller().forgotPassword(forgot("loja@example.com"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("rst_abc123", ((ForgotPasswordResponse) response.getBody()).resetToken());
    }

    @Test
    void forgotInDevWithUnknownEmailReturns204WithoutToken() {
        authProperties.setExposeResetToken(true);
        when(passwordReset.forgot(any())).thenReturn(PasswordResetUseCase.ForgotResult.empty());

        ResponseEntity<?> response = controller().forgotPassword(forgot("nao.existe@example.com"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void forgotInProdAlwaysReturns204EvenWhenEmailExists() {
        // Produção (expose-reset-token=false): o token NUNCA sai na resposta,
        // mesmo quando o e-mail existe — anti-enumeração (critério 2).
        authProperties.setExposeResetToken(false);
        when(passwordReset.forgot(any()))
                .thenReturn(PasswordResetUseCase.ForgotResult.of("rst_abc123"));

        ResponseEntity<?> response = controller().forgotPassword(forgot("loja@example.com"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void forgotInProdUnknownEmailReturns204() {
        authProperties.setExposeResetToken(false);
        when(passwordReset.forgot(any())).thenReturn(PasswordResetUseCase.ForgotResult.empty());

        ResponseEntity<?> response = controller().forgotPassword(forgot("nao.existe@example.com"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void resetPasswordDelegatesAndReturns204() {
        ResponseEntity<Void> response =
                controller().resetPassword(new ResetPasswordRequest("rst_abc", "nova-senha-123"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(passwordReset).reset(new PasswordResetUseCase.ResetCommand("rst_abc", "nova-senha-123"));
    }
}
