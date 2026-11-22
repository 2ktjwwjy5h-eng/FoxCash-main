package com.foxcash.infrastructure.security;

import com.foxcash.domain.exception.MerchantAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link MerchantAccessGuard} (Spec 18, ADR-009): o recurso só é
 * liberado quando o principal autenticado (UUID) é o dono do merchantId.
 */
class MerchantAccessGuardTest {

    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final UUID MERCHANT_B = UUID.randomUUID();

    private final MerchantAccessGuard guard = new MerchantAccessGuard();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAsMerchant(UUID merchantId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(merchantId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_MERCHANT"))));
    }

    @Test
    void ownerMerchantIsAllowed() {
        authenticateAsMerchant(MERCHANT_A);

        assertThatCode(() -> guard.requireMerchantAccess(MERCHANT_A)).doesNotThrowAnyException();
    }

    @Test
    void otherMerchantIsDenied() {
        authenticateAsMerchant(MERCHANT_A);

        assertThatThrownBy(() -> guard.requireMerchantAccess(MERCHANT_B))
                .isInstanceOf(MerchantAccessDeniedException.class);
    }

    @Test
    void nonUuidPrincipalIsDenied() {
        // Admin (principal string) e master (UUID distinto) não passam nos endpoints de lojista.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThatThrownBy(() -> guard.requireMerchantAccess(MERCHANT_A))
                .isInstanceOf(MerchantAccessDeniedException.class);
    }

    @Test
    void anonymousIsDenied() {
        // Sem autenticação (nunca deveria chegar aqui — SecurityConfig bloqueia antes com 401).
        assertThatThrownBy(() -> guard.requireMerchantAccess(MERCHANT_A))
                .isInstanceOf(MerchantAccessDeniedException.class);
    }
}
