package com.foxcash.domain.model.master;

import java.util.Arrays;

/**
 * Catalogo de permissoes de acoes administrativas do master (Spec 69) — fonte
 * unica do contrato, reutilizado pelo {@code MasterPermissionGuard}, pela
 * configuracao de step-up e pelo {@code GET /master/me} (Spec 71).
 *
 * <p>Namespace por dominio, cola 1:1 com {@code MasterAuditAction} (Spec 37) e
 * com as rotas existentes de {@code /api/v1/master/**}. A CONCESSAO e por
 * nivel (decisao do usuario 2026-08-11): todo master ativo (OPERATOR) tem as 8
 * permissoes operacionais; apenas o ADMIN ({@code can_manage_masters}) tem
 * {@link #MASTER_PROFILES_MANAGE}. Sem tabela {@code master_permissions} — o
 * catalogo e o contrato.
 */
public enum MasterPermission {

    /** Suspender lojista ({@code POST /api/v1/master/merchants/{id}/suspend}). */
    MERCHANT_SUSPEND("merchant.suspend"),

    /** Liberar lojista ({@code POST /api/v1/master/merchants/{id}/release}). */
    MERCHANT_RELEASE("merchant.release"),

    /** Bloquear saques do lojista ({@code .../withdrawals/block}). */
    WITHDRAWAL_BLOCK("withdrawal.block"),

    /** Liberar saques do lojista ({@code .../withdrawals/release}). */
    WITHDRAWAL_RELEASE("withdrawal.release"),

    /** Saque administrativo em nome do lojista ({@code .../withdrawals/administrative}). */
    WITHDRAWAL_ADMINISTRATIVE("withdrawal.administrative"),

    /** Baixa (write-off) de receivable ({@code POST /api/v1/master/receivables/{id}/write-off}). */
    RECEIVABLE_WRITE_OFF("receivable.write_off"),

    /** Cobranca manual de fatura ({@code POST /api/v1/master/merchants/{id}/invoices/{id}/collect}). */
    INVOICE_COLLECT("invoice.collect"),

    /** Gestao de perfis master ({@code POST/GET /api/v1/master/profiles} + activate/deactivate). */
    MASTER_PROFILES_MANAGE("master.profiles.manage"),

    /** Cancelamento forcado de broadcast ({@code POST /api/v1/master/broadcasts/{id}/force-cancel}). */
    BROADCAST_FORCE_CANCEL("broadcast.force_cancel"),

    /** Config fina por lojista ({@code GET/PUT /api/v1/master/merchants/{id}/config} — Spec 98). */
    MERCHANT_CONFIG("merchant.config"),

    /** Debitar saldo manual ({@code POST .../config/debit} — Spec 98; sensível com step-up). */
    MERCHANT_DEBIT("merchant.debit"),

    /** Banir lojista ({@code POST /api/v1/master/merchants/{id}/ban} — Spec 98; sensível com step-up). */
    MERCHANT_BAN("merchant.ban"),

    /** Gestão de posts da Comunidade ({@code /api/v1/master/community/posts} — Spec 119; OPERATOR). */
    COMMUNITY_MANAGE("community.manage");

    private final String value;

    MasterPermission(String value) {
        this.value = value;
    }

    /** Valor canônico ({@code merchant.suspend}, {@code withdrawal.block}, ...). */
    public String value() {
        return value;
    }

    /** Resolve o valor canônico; {@code IllegalArgumentException} se fora do catálogo. */
    public static MasterPermission fromValue(String value) {
        return Arrays.stream(values())
                .filter(p -> p.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Permissao desconhecida: " + value));
    }
}
