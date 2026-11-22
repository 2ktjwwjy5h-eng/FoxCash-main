package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.domain.exception.MerchantNotFoundException;
import com.foxcash.domain.model.merchant.Merchant;
import com.foxcash.domain.port.in.MerchantMfaUseCase;
import com.foxcash.domain.port.out.MerchantRepository;
import com.foxcash.infrastructure.inbound.rest.dto.PortalMeResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: rotas do portal do lojista (Spec 20), role
 * {@code ROLE_MERCHANT_PORTAL} (SecurityConfig).
 *
 * <p>{@code GET /api/v1/portal/me} devolve o perfil do lojista logado
 * (principal = merchantId) — endpoint de sanidade da sessao. Spec 94: a
 * resposta inclui {@code mfaActive} (estado do MFA para a página Segurança).
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

    private final MerchantRepository merchantRepository;
    private final MerchantMfaUseCase mfa;

    public PortalController(MerchantRepository merchantRepository, MerchantMfaUseCase mfa) {
        this.merchantRepository = merchantRepository;
        this.mfa = mfa;
    }

    @GetMapping("/me")
    public PortalMeResponse me(Authentication authentication) {
        UUID merchantId = (UUID) authentication.getPrincipal();
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new MerchantNotFoundException(merchantId));
        return PortalMeResponse.from(merchant, mfa.hasActiveMfa(merchantId));
    }
}
