package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.port.in.MasterProfileUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.MasterMeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes do adaptador REST do {@code GET /api/v1/master/me} (Spec 71, fatia 1):
 * shape completo do perfil (role/permissions/mfaActive), uso do principal
 * autenticado (nunca do body — sem IDOR) e ausencia de hash/PII na resposta.
 *
 * <p>O 401 de rota é responsabilidade do {@code MasterAuthFilter} +
 * {@code ROLE_MASTER} (SecurityConfig) — coberto pela suíte E2E; aqui valida-se
 * o contrato do controller.
 */
class MasterMeControllerTest {

    private static final UUID MASTER_ID = UUID.randomUUID();

    private final MasterProfileUseCase profileUseCase = mock(MasterProfileUseCase.class);
    private final Authentication authentication = mock(Authentication.class);
    private MasterMeController controller;

    @BeforeEach
    void setUp() {
        controller = new MasterMeController(profileUseCase);
        when(authentication.getPrincipal()).thenReturn(MASTER_ID);
    }

    @Test
    void meReturnsFullShapeForOperator() {
        when(profileUseCase.selfProfile(MASTER_ID)).thenReturn(
                new MasterProfileUseCase.SelfProfile(MASTER_ID, "operador", "Operador", "OPERATOR",
                        List.of("merchant.suspend", "merchant.release", "withdrawal.block",
                                "withdrawal.release", "withdrawal.administrative",
                                "receivable.write_off", "invoice.collect"),
                        false, false));

        MasterMeResponse response = controller.me(authentication);

        assertThat(response.masterId()).isEqualTo(MASTER_ID);
        assertThat(response.username()).isEqualTo("operador");
        assertThat(response.name()).isEqualTo("Operador");
        assertThat(response.role()).isEqualTo("OPERATOR");
        // Bloco operacional completo (Spec 69 — 7 permissões, nunca [] para ativo).
        assertThat(response.permissions()).containsExactlyInAnyOrder(
                "merchant.suspend", "merchant.release", "withdrawal.block",
                "withdrawal.release", "withdrawal.administrative",
                "receivable.write_off", "invoice.collect");
        assertThat(response.permissions()).hasSize(7);
        assertThat(response.canManageMasters()).isFalse();
        assertThat(response.mfaActive()).isFalse();
    }

    @Test
    void meReturnsAdminRoleWithProfilesManagePermission() {
        when(profileUseCase.selfProfile(MASTER_ID)).thenReturn(
                new MasterProfileUseCase.SelfProfile(MASTER_ID, "admin", "Admin", "ADMIN",
                        List.of("merchant.suspend", "merchant.release", "withdrawal.block",
                                "withdrawal.release", "withdrawal.administrative",
                                "receivable.write_off", "invoice.collect", "master.profiles.manage"),
                        true, true));

        MasterMeResponse response = controller.me(authentication);

        assertThat(response.role()).isEqualTo("ADMIN");
        assertThat(response.permissions()).contains("master.profiles.manage");
        assertThat(response.permissions()).hasSize(8);
        assertThat(response.canManageMasters()).isTrue();
        assertThat(response.mfaActive()).isTrue();
    }

    @Test
    void usesAuthenticatedMasterIdFromPrincipalNotFromRequest() {
        // A rota não recebe masterId do client — o principal do MasterAuthFilter
        // é a única fonte (sem IDOR; o console nunca passa o id no body/query).
        when(profileUseCase.selfProfile(MASTER_ID)).thenReturn(
                new MasterProfileUseCase.SelfProfile(MASTER_ID, "operador", "Operador", "OPERATOR",
                        List.of("merchant.suspend"), false, false));

        controller.me(authentication);

        verify(profileUseCase).selfProfile(MASTER_ID);
    }

    @Test
    void responseNeverContainsPasswordHash() {
        when(profileUseCase.selfProfile(MASTER_ID)).thenReturn(
                new MasterProfileUseCase.SelfProfile(MASTER_ID, "operador", "Operador", "OPERATOR",
                        List.of("merchant.suspend"), false, false));

        MasterMeResponse response = controller.me(authentication);

        // Por construção o record não carrega passwordHash — garantia dupla de
        // que o hash nunca vaza na resposta do /me.
        assertThat(response.toString()).doesNotContain("passwordHash");
        assertThat(response.toString()).doesNotContain("$2a$");
    }
}
