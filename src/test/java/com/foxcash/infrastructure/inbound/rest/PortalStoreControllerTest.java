package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.StoreAlreadyExistsException;
import com.foxcash.domain.exception.StoreNotFoundException;
import com.foxcash.domain.model.store.Store;
import com.foxcash.domain.port.in.StoreManagementUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.StoreRequest;
import com.foxcash.infrastructure.inbound.rest.dto.StoreResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST da loja do portal (Spec 48): o merchantId vem do
 * principal da sessao (ROLE_MERCHANT_PORTAL), POST cria (201) e PATCH atualiza
 * (200); 409/404 de dominio propagam.
 */
class PortalStoreControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    private final StoreManagementUseCase storeManagement = mock(StoreManagementUseCase.class);
    private final Authentication authentication = mock(Authentication.class);

    private PortalStoreController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalStoreController(storeManagement);
        when(authentication.getPrincipal()).thenReturn(MERCHANT_ID);
    }

    private Store store(String name) {
        return Store.create(MERCHANT_ID, name, null, null, null, null, "#9B59B6", null, null, null, NOW);
    }

    private StoreRequest request(String name) {
        return new StoreRequest(name, null, null, null,
                List.of(new StoreRequest.SocialLinkRequest("Instagram", "https://instagram.com/loja")),
                "#9B59B6", null, null, null);
    }

    @Test
    void getReturnsStoreOfPrincipal() {
        when(storeManagement.get(MERCHANT_ID)).thenReturn(store("Bella"));

        StoreResponse response = controller.get(authentication);

        assertThat(response.name()).isEqualTo("Bella");
        assertThat(response.color()).isEqualTo("#9B59B6");
    }

    @Test
    void getWithoutStorePropagates404() {
        when(storeManagement.get(MERCHANT_ID)).thenThrow(new StoreNotFoundException());

        assertThatThrownBy(() -> controller.get(authentication))
                .isInstanceOf(StoreNotFoundException.class);
    }

    @Test
    void createReturns201WithBody() {
        when(storeManagement.create(any(), any())).thenAnswer(inv -> {
            StoreManagementUseCase.SaveStoreCommand cmd = inv.getArgument(1);
            return Store.create(MERCHANT_ID, cmd.name(), cmd.logoUrl(), cmd.bannerUrl(),
                    cmd.description(), cmd.socialLinks(), cmd.color(), cmd.emoji(), cmd.title(),
                    cmd.footer(), NOW);
        });

        ResponseEntity<StoreResponse> response = controller.create(authentication, request("Bella Premium"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().name()).isEqualTo("Bella Premium");
        assertThat(response.getBody().socialLinks()).hasSize(1);
        verify(storeManagement).create(any(), any());
    }

    @Test
    void createWhenExistsPropagates409() {
        when(storeManagement.create(any(), any())).thenThrow(new StoreAlreadyExistsException());

        assertThatThrownBy(() -> controller.create(authentication, request("Bella")))
                .isInstanceOf(StoreAlreadyExistsException.class);
    }

    @Test
    void updateReturns200WithBody() {
        when(storeManagement.update(any(), any())).thenAnswer(inv -> {
            StoreManagementUseCase.SaveStoreCommand cmd = inv.getArgument(1);
            return Store.create(MERCHANT_ID, cmd.name(), null, null, null, null, cmd.color(), null, null, null, NOW);
        });

        ResponseEntity<StoreResponse> response = controller.update(authentication,
                new StoreRequest("Bella Nova", null, null, null, null, "#8E44AD", null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().name()).isEqualTo("Bella Nova");
        verify(storeManagement).update(any(), any());
    }

    @Test
    void updateWithoutStorePropagates404() {
        when(storeManagement.update(any(), any())).thenThrow(new StoreNotFoundException());

        assertThatThrownBy(() -> controller.update(authentication, request("X")))
                .isInstanceOf(StoreNotFoundException.class);
    }
}
