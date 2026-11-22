package com.foxcash.domain.model.master;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do catalogo de permissoes do master (Spec 69, F0 — criterio 1): as 9
 * permissoes do catalogo com os valores canonicos (namespace por dominio, cola
 * 1:1 com {@code MasterAuditAction} e com as rotas existentes) e resolucao
 * por valor com rejeicao de valor fora do catalogo.
 */
class MasterPermissionTest {

    @Test
    void catalogHasExactlyTwelvePermissionsMatchingRoutes() {
        assertThat(Arrays.stream(MasterPermission.values()).toList()).hasSize(13);

        // Cola 1:1 com as rotas existentes de /api/v1/master/** (design D1.2).
        assertThat(MasterPermission.MERCHANT_SUSPEND.value()).isEqualTo("merchant.suspend");
        assertThat(MasterPermission.MERCHANT_RELEASE.value()).isEqualTo("merchant.release");
        assertThat(MasterPermission.WITHDRAWAL_BLOCK.value()).isEqualTo("withdrawal.block");
        assertThat(MasterPermission.WITHDRAWAL_RELEASE.value()).isEqualTo("withdrawal.release");
        assertThat(MasterPermission.WITHDRAWAL_ADMINISTRATIVE.value()).isEqualTo("withdrawal.administrative");
        assertThat(MasterPermission.RECEIVABLE_WRITE_OFF.value()).isEqualTo("receivable.write_off");
        assertThat(MasterPermission.INVOICE_COLLECT.value()).isEqualTo("invoice.collect");
        assertThat(MasterPermission.MASTER_PROFILES_MANAGE.value()).isEqualTo("master.profiles.manage");
        // Spec 89: cancelamento forçado de broadcast (9ª — aditiva ao catálogo).
        assertThat(MasterPermission.BROADCAST_FORCE_CANCEL.value()).isEqualTo("broadcast.force_cancel");
        // Spec 98: config fina por lojista + débito manual + banimento (10ª-12ª).
        assertThat(MasterPermission.MERCHANT_CONFIG.value()).isEqualTo("merchant.config");
        assertThat(MasterPermission.MERCHANT_DEBIT.value()).isEqualTo("merchant.debit");
        assertThat(MasterPermission.MERCHANT_BAN.value()).isEqualTo("merchant.ban");
        // Spec 119: gestão de posts da Comunidade (13ª — aditiva ao catálogo).
        assertThat(MasterPermission.COMMUNITY_MANAGE.value()).isEqualTo("community.manage");
    }

    @Test
    void valuesAreUnique() {
        long distinct = Arrays.stream(MasterPermission.values())
                .map(MasterPermission::value)
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(MasterPermission.values().length);
    }

    @Test
    void fromValueResolvesCanonicalValue() {
        assertThat(MasterPermission.fromValue("merchant.suspend"))
                .isEqualTo(MasterPermission.MERCHANT_SUSPEND);
        assertThat(MasterPermission.fromValue("master.profiles.manage"))
                .isEqualTo(MasterPermission.MASTER_PROFILES_MANAGE);
    }

    @Test
    void fromValueRejectsUnknownPermission() {
        assertThatThrownBy(() -> MasterPermission.fromValue("merchant.delete"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
