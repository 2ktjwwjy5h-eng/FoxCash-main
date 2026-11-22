package com.foxcash.infrastructure.security;

import com.foxcash.domain.exception.MerchantAccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Autorização por recurso (Spec 18, ADR-009) — fecha o IDOR.
 *
 * <p>O principal de um {@code ROLE_MERCHANT} é o UUID do próprio lojista (Spec
 * 12). Este guard garante que o {@code merchantId} do recurso acessado
 * (path/query/body) pertence ao principal autenticado. Admin (principal
 * string) e master (UUID distinto) possuem rotas próprias
 * ({@code /admin/**}, {@code /master/**}) e portanto são negados aqui.
 *
 * <p>Não-autenticado nunca chega aqui: o {@link SecurityConfig} bloqueia
 * antes com 401.
 */
@Component
public class MerchantAccessGuard {

    /**
     * Lança {@link MerchantAccessDeniedException} se o recurso
     * {@code resourceMerchantId} não pertencer ao lojista autenticado.
     */
    public void requireMerchantAccess(UUID resourceMerchantId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth == null ? null : auth.getPrincipal();
        if (principal instanceof UUID uuid && uuid.equals(resourceMerchantId)) {
            return;
        }
        throw new MerchantAccessDeniedException();
    }
}
