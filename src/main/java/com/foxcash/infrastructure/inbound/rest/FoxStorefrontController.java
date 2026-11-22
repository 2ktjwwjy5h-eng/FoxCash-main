package com.foxcash.infrastructure.inbound.rest;

import com.foxcash.application.service.StoreButtonPayloadCodec;
import com.foxcash.domain.exception.MerchantAccessDeniedException;
import com.foxcash.domain.port.in.StorefrontQueryUseCase;
import com.foxcash.infrastructure.inbound.rest.dto.StorefrontResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Adaptador REST: storefront (Spec 48/50) — {@code GET /api/v1/foxcash/storefront}
 * (API key do lojista, {@code ROLE_MERCHANT}) devolve a configuracao completa
 * da loja do principal que os bots renderizam (Spec 52): identidade + planos
 * ativos (Spec 49) + botões do menu ativos em ordem (Spec 50). Sem loja → 404
 * {@code store_not_found}. Admin/master (principal nao-UUID) nao tem loja —
 * negados com 403 (o storefront e por lojista).
 */
@RestController
@RequestMapping("/api/v1/foxcash/storefront")
public class FoxStorefrontController {

    private final StorefrontQueryUseCase storefrontQuery;
    private final StoreButtonPayloadCodec buttonPayloadCodec;

    public FoxStorefrontController(StorefrontQueryUseCase storefrontQuery,
                                   StoreButtonPayloadCodec buttonPayloadCodec) {
        this.storefrontQuery = storefrontQuery;
        this.buttonPayloadCodec = buttonPayloadCodec;
    }

    @GetMapping
    public StorefrontResponse get(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof UUID merchantId)) {
            throw new MerchantAccessDeniedException();
        }
        StorefrontQueryUseCase.Storefront storefront = storefrontQuery.get(merchantId);
        var buttons = storefront.activeButtons().stream()
                .map(b -> StorefrontResponse.ButtonStorefrontResponse.from(
                        b, buttonPayloadCodec.decode(b.payload())))
                .toList();
        return StorefrontResponse.from(storefront, buttons);
    }
}
