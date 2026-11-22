package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.ApiKeyNotFoundException;
import com.foxcash.domain.exception.TooManyApiKeysException;
import com.foxcash.domain.model.apikey.ApiKeyScope;
import com.foxcash.domain.model.apikey.ApiKeyStatus;
import com.foxcash.domain.model.apikey.MerchantApiKey;
import com.foxcash.domain.port.in.ApiKeyManagementUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.ApiKeyResponse;
import com.foxcash.infrastructure.inbound.rest.dto.CreateApiKeyRequest;
import com.foxcash.infrastructure.inbound.rest.dto.CreateApiKeyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST de credenciais de API do portal (Spec 24): o
 * merchantId vem do principal da sessao (ROLE_MERCHANT_PORTAL), o segredo e
 * devolvido uma vez no create, e erros de dominio propagam.
 */
class PortalCredentialsControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID KEY_ID = UUID.randomUUID();

    private final ApiKeyManagementUseCase apiKeyManagement = mock(ApiKeyManagementUseCase.class);
    private final Authentication authentication = mock(Authentication.class);

    private PortalCredentialsController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalCredentialsController(apiKeyManagement);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    private MerchantApiKey key() {
        return MerchantApiKey.restore(KEY_ID, MERCHANT_ID, "Bot", "mch_abcdef",
                "hash", ApiKeyStatus.ACTIVE, Set.of(ApiKeyScope.CHARGES), null, Instant.now());
    }

    @Test
    void listReturnsKeysWithoutSecret() {
        when(apiKeyManagement.list(MERCHANT_ID)).thenReturn(List.of(key()));

        List<ApiKeyResponse> response = controller.list(authentication);

        assertThat(response).hasSize(1);
        ApiKeyResponse first = response.get(0);
        assertThat(first.id()).isEqualTo(KEY_ID);
        assertThat(first.prefix()).isEqualTo("mch_abcdef");
        assertThat(first.status()).isEqualTo("ACTIVE");
        // Nunca expoe o hash nem o segredo.
        assertThat(first.toString()).doesNotContain("hash");
    }

    @Test
    void createReturns201WithSecretOnce() {
        when(apiKeyManagement.create(any(), any()))
                .thenReturn(new ApiKeyManagementUseCase.CreateResult(KEY_ID, "mch_secret1234567890"));

        ResponseEntity<CreateApiKeyResponse> response = controller.create(authentication,
                new CreateApiKeyRequest("Bot de produção", List.of("charges", "withdrawals")));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().id()).isEqualTo(KEY_ID);
        assertThat(response.getBody().secret()).isEqualTo("mch_secret1234567890");
        verify(apiKeyManagement).create(any(), any());
    }

    @Test
    void createWithInvalidScopeThrowsIllegalArgument() {
        assertThatThrownBy(() -> controller.create(authentication,
                new CreateApiKeyRequest("Bot", List.of("hacker"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createWithTooManyKeysPropagatesConflict() {
        when(apiKeyManagement.create(any(), any()))
                .thenThrow(new TooManyApiKeysException(5));

        assertThatThrownBy(() -> controller.create(authentication,
                new CreateApiKeyRequest("Bot", List.of("charges"))))
                .isInstanceOf(TooManyApiKeysException.class);
    }

    @Test
    void revokeReturnsNoContent() {
        ResponseEntity<Void> response = controller.revoke(authentication, KEY_ID);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(apiKeyManagement).revoke(MERCHANT_ID, KEY_ID);
    }

    @Test
    void revokeUnknownKeyPropagates404() {
        doThrow(new ApiKeyNotFoundException(KEY_ID))
                .when(apiKeyManagement).revoke(MERCHANT_ID, KEY_ID);

        assertThatThrownBy(() -> controller.revoke(authentication, KEY_ID))
                .isInstanceOf(ApiKeyNotFoundException.class);
    }
}
