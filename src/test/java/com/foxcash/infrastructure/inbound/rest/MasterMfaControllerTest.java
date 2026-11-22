package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.MasterStepUpService;
import com.foxcash.domain.exception.InvalidMfaCodeException;
import com.foxcash.domain.port.in.MasterMfaUseCase;
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
 * Testes do adaptador REST do MFA do master (Spec 44): delegação para o use
 * case com o {@code masterId} do principal autenticado, formato das respostas
 * (URI + chave manual 1x, recovery codes 1x) e propagação de 401 uniforme.
 * Spec 69: teste do endpoint {@code step-up} (re-prova de posse).
 *
 * <p>O 401/403 de rota é responsabilidade dos filtros de segurança
 * (MasterAuthFilter + ROLE_MASTER) — aqui cobre-se o contrato do controller.
 */
class MasterMfaControllerTest {

    private static final UUID MASTER_ID = UUID.randomUUID();

    private final MasterMfaUseCase mfa = mock(MasterMfaUseCase.class);
    private final MasterStepUpService stepUpService = mock(MasterStepUpService.class);
    private final Authentication authentication = mock(Authentication.class);
    private MasterMfaController controller;

    @BeforeEach
    void setUp() {
        controller = new MasterMfaController(mfa, stepUpService);
        when(authentication.getPrincipal()).thenReturn(MASTER_ID);
    }

    @Test
    void activateReturnsUriAndManualEntryKeyFromPrincipal() {
        when(mfa.activate(MASTER_ID)).thenReturn(
                new MasterMfaUseCase.ActivateResult(
                        "otpauth://totp/FoxCash:master?secret=ABC", "ABC"));

        ResponseEntity<Map<String, String>> response = controller.activate(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("otpauthUri",
                        "otpauth://totp/FoxCash:master?secret=ABC")
                .containsEntry("manualEntryKey", "ABC");
        verify(mfa).activate(MASTER_ID);
    }

    @Test
    void confirmReturnsRecoveryCodesAndUsesRequestCode() {
        MfaCodeRequest request = new MfaCodeRequest("123456");
        when(mfa.confirm(MASTER_ID, "123456")).thenReturn(
                new MasterMfaUseCase.ConfirmResult(List.of("mrc_a", "mrc_b")));

        ResponseEntity<Map<String, Object>> response = controller.confirm(authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("recoveryCodes", List.of("mrc_a", "mrc_b"));
        verify(mfa).confirm(MASTER_ID, "123456");
    }

    @Test
    void deactivateReturnsNoContentAndUsesRequestCodeAndPassword() {
        MfaDeactivateRequest request = new MfaDeactivateRequest("123456", "senha-certa");

        ResponseEntity<Void> response = controller.deactivate(authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(mfa).deactivate(MASTER_ID, "123456", "senha-certa");
    }

    @Test
    void regenerateRecoveryCodesReturnsNewCodesAndUsesRequestCode() {
        MfaCodeRequest request = new MfaCodeRequest("123456");
        when(mfa.regenerateRecoveryCodes(MASTER_ID, "123456"))
                .thenReturn(List.of("mrc_novo1", "mrc_novo2"));

        ResponseEntity<Map<String, Object>> response =
                controller.regenerateRecoveryCodes(authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("recoveryCodes", List.of("mrc_novo1", "mrc_novo2"));
        verify(mfa).regenerateRecoveryCodes(MASTER_ID, "123456");
    }

    @Test
    void invalidMfaCodePropagatesForUniform401() {
        // O controller não traduz a exceção — o GlobalExceptionHandler devolve o
        // 401 uniforme (sem oráculo). Aqui garante-se a propagação intacta.
        when(mfa.confirm(MASTER_ID, "000000"))
                .thenThrow(new InvalidMfaCodeException());

        assertThatThrownBy(() -> controller.confirm(authentication, new MfaCodeRequest("000000")))
                .isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    void usesAuthenticatedMasterIdFromPrincipalNotFromRequest() {
        // Admin NÃO gerencia TOTP de terceiros: o masterId vem do principal
        // (o token autenticado), nunca de um campo do corpo — evita escalada.
        MasterMfaUseCase.ActivateResult result = new MasterMfaUseCase.ActivateResult("uri", "secret");
        when(mfa.activate(MASTER_ID)).thenReturn(result);

        controller.activate(authentication);

        verify(mfa).activate(MASTER_ID);
    }

    // ---- Spec 69: step-up MFA (re-prova de posse para ações sensíveis) ----

    @Test
    void stepUpReturnsOkAndDelegatesWithCodeAndBearerToken() {
        ResponseEntity<Void> response =
                controller.stepUp(authentication, new MfaCodeRequest("123456"), "Bearer mst_token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(stepUpService).stepUp(MASTER_ID, "123456", "Bearer mst_token");
    }

    @Test
    void stepUpWithoutBearerStillDelegates() {
        // O service resolve a sessão a partir do header; ausente → 403
        // step_up_required (falha fechada) — o controller apenas delega.
        ResponseEntity<Void> response = controller.stepUp(authentication, new MfaCodeRequest("123456"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(stepUpService).stepUp(MASTER_ID, "123456", null);
    }
}
