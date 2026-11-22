package com.foxcash.application.service;

import com.foxcash.domain.exception.MasterPermissionDeniedException;
import com.foxcash.domain.model.master.Master;
import com.foxcash.domain.model.master.MasterPermission;
import com.foxcash.domain.port.out.MasterRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes do {@link MasterPermissionGuard} (Spec 69, F1 — critérios 3/4):
 * autorização tier-based — todo master ATIVO tem as 7 permissões operacionais;
 * {@code master.profiles.manage} apenas ADMIN ({@code can_manage_masters});
 * master inativo/inexistente → 403 {@code forbidden}.
 */
class MasterPermissionGuardTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID OPERATOR_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    private final MasterRepository masterRepository = mock(MasterRepository.class);
    private final MasterPermissionGuard guard = new MasterPermissionGuard(masterRepository);

    private Master operator() {
        return Master.restore(OPERATOR_ID, "operador", "hash", "Operador", true, false, NOW);
    }

    private Master admin() {
        return Master.restore(ADMIN_ID, "admin", "hash", "Admin", true, true, NOW);
    }

    private void stubOperator() {
        when(masterRepository.findById(OPERATOR_ID)).thenReturn(Optional.of(operator()));
    }

    private void stubAdmin() {
        when(masterRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin()));
    }

    /** As 7 permissões operacionais do bloco OPERATOR (decisão do usuário 2026-08-11). */
    private static MasterPermission[] operationalPermissions() {
        return new MasterPermission[]{
                MasterPermission.MERCHANT_SUSPEND,
                MasterPermission.MERCHANT_RELEASE,
                MasterPermission.WITHDRAWAL_BLOCK,
                MasterPermission.WITHDRAWAL_RELEASE,
                MasterPermission.WITHDRAWAL_ADMINISTRATIVE,
                MasterPermission.RECEIVABLE_WRITE_OFF,
                MasterPermission.INVOICE_COLLECT
        };
    }

    @Test
    void operatorExecutesAllSevenOperationalPermissions() {
        stubOperator();
        for (MasterPermission permission : operationalPermissions()) {
            assertThatCode(() -> guard.requirePermission(OPERATOR_ID, permission))
                    .as("OPERATOR deve executar %s", permission)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void operatorIsDeniedOnProfileManagement() {
        stubOperator();
        assertThatThrownBy(() -> guard.requirePermission(OPERATOR_ID, MasterPermission.MASTER_PROFILES_MANAGE))
                .isInstanceOf(MasterPermissionDeniedException.class);
    }

    @Test
    void adminExecutesEverythingIncludingProfileManagement() {
        stubAdmin();
        for (MasterPermission permission : MasterPermission.values()) {
            assertThatCode(() -> guard.requirePermission(ADMIN_ID, permission))
                    .as("ADMIN deve executar %s", permission)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void inactiveMasterIsDeniedOnAnyPermission() {
        when(masterRepository.findById(OPERATOR_ID))
                .thenReturn(Optional.of(Master.restore(OPERATOR_ID, "op", "hash", "Op", false, false, NOW)));
        assertThatThrownBy(() -> guard.requirePermission(OPERATOR_ID, MasterPermission.MERCHANT_SUSPEND))
                .isInstanceOf(MasterPermissionDeniedException.class);
    }

    @Test
    void unknownMasterIsDenied() {
        when(masterRepository.findById(OPERATOR_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requirePermission(OPERATOR_ID, MasterPermission.MERCHANT_SUSPEND))
                .isInstanceOf(MasterPermissionDeniedException.class);
    }

    @Test
    void stringOverloadResolvesAgainstCatalog() {
        stubOperator();
        assertThatCode(() -> guard.requirePermission(OPERATOR_ID, "merchant.suspend"))
                .doesNotThrowAnyException();
        // Valor fora do catálogo → IllegalArgumentException (não 403 — config/erro interno).
        assertThatThrownBy(() -> guard.requirePermission(OPERATOR_ID, "merchant.delete"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void returnsTheActiveMasterActor() {
        stubAdmin();
        Master actor = guard.requirePermission(ADMIN_ID, MasterPermission.MASTER_PROFILES_MANAGE);
        assertThat(actor.id()).isEqualTo(ADMIN_ID);
    }
}
