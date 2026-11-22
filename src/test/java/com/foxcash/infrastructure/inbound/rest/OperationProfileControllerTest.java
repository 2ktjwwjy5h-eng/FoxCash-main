package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.OperationProfileService;
import com.foxcash.infrastructure.inbound.rest.dto.CreateProfileRequest;
import com.foxcash.infrastructure.inbound.rest.dto.ProfileResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ProfilesResponse;
import com.foxcash.infrastructure.inbound.rest.dto.ReplaceProfileBotsRequest;
import com.foxcash.infrastructure.inbound.rest.dto.UpdateProfileRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST de perfis de operação (Spec 122/T2.3 + Spec 139): o
 * merchantId vem do principal (ROLE_MERCHANT_PORTAL), o header {@code X-Profile-ID}
 * é repassado (UUID válido → id; ausente/inválido → null → default), POST 201
 * repassa scope, PATCH delega com o id do path e o PUT /{id}/bots repassa os ids.
 */
class OperationProfileControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();

    private final OperationProfileService service = mock(OperationProfileService.class);
    private final Authentication authentication = mock(Authentication.class);

    private OperationProfileController controller;

    @BeforeEach
    void setUp() {
        controller = new OperationProfileController(service);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    private ProfileResponse profile() {
        return new ProfileResponse(PROFILE_ID, "Global", "GENERAL", "PADRAO", List.of(), List.of());
    }

    @Test
    void getRepassaHeaderValidoComoUuid() {
        when(service.get(MERCHANT_ID, PROFILE_ID)).thenReturn(
                new ProfilesResponse(List.of(), PROFILE_ID));

        ProfilesResponse response = controller.get(authentication, PROFILE_ID.toString());

        assertThat(response.activeProfileId()).isEqualTo(PROFILE_ID);
        verify(service).get(MERCHANT_ID, PROFILE_ID);
    }

    @Test
    void getSemHeaderRepassaNull() {
        when(service.get(MERCHANT_ID, null)).thenReturn(new ProfilesResponse(List.of(), null));

        ProfilesResponse response = controller.get(authentication, null);

        assertThat(response.profiles()).isEmpty();
        verify(service).get(MERCHANT_ID, null);
    }

    @Test
    void getComHeaderInvalidoRepassaNull() {
        // Header não-UUID → não vira id (nunca vaza; o service cai para o default).
        controller.get(authentication, "nao-e-um-uuid");

        verify(service).get(MERCHANT_ID, null);
    }

    @Test
    void createDelegaComScopeEDevolve201() {
        CreateProfileRequest request = new CreateProfileRequest("Bot Padrão", null, "BOT_ONLY");
        when(service.create(MERCHANT_ID, "Bot Padrão", null, "BOT_ONLY")).thenReturn(profile());

        ResponseEntity<ProfileResponse> response = controller.create(authentication, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().name()).isEqualTo("Global");
        verify(service).create(MERCHANT_ID, "Bot Padrão", null, "BOT_ONLY");
    }

    @Test
    void updateDelegaComIdDoPathEScope() {
        UpdateProfileRequest request = new UpdateProfileRequest(null, "pagarme", "GATEWAY_ONLY");
        when(service.update(MERCHANT_ID, PROFILE_ID, null, "pagarme", "GATEWAY_ONLY"))
                .thenReturn(profile());

        ProfileResponse response = controller.update(authentication, PROFILE_ID, request);

        assertThat(response.gatewayRoute()).isEqualTo("PADRAO");
        verify(service).update(MERCHANT_ID, PROFILE_ID, null, "pagarme", "GATEWAY_ONLY");
    }

    @Test
    void replaceBotsDelegaComIdDoPathELista() {
        UUID botA = UUID.randomUUID();
        UUID botB = UUID.randomUUID();
        ReplaceProfileBotsRequest request = new ReplaceProfileBotsRequest(List.of(botA, botB));
        when(service.replaceBots(MERCHANT_ID, PROFILE_ID, List.of(botA, botB))).thenReturn(profile());

        ProfileResponse response = controller.replaceBots(authentication, PROFILE_ID, request);

        assertThat(response.scope()).isEqualTo("GENERAL");
        verify(service).replaceBots(MERCHANT_ID, PROFILE_ID, List.of(botA, botB));
    }
}
