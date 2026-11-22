package com.foxcash.infrastructure.inbound.rest.dto;

import com.foxcash.domain.model.plan.Plan;
import com.foxcash.domain.model.store.StoreButton;
import com.foxcash.domain.model.store.StoreButtonAction;
import com.foxcash.domain.model.store.StoreMessage;
import com.foxcash.domain.port.in.StorefrontQueryUseCase.Storefront;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Storefront (Spec 48/49/50/51) — a configuracao completa da loja que os bots
 * renderizam (Spec 52): identidade + planos ATIVOS (Spec 49, card visual — o
 * payload das ações NUNCA sai aqui) + botões do menu ATIVOS em ordem (Spec 50,
 * label/action/payload tipado — o bot renderiza o fluxo; o payload é devolvido
 * pois quem chama é o lojista dono via API key) + mensagens por etapa ATIVAS
 * (Spec 51, {@code messages{event: text}} — o bot renderiza no fluxo).
 */
public record StorefrontResponse(
        StoreResponse store,
        List<ButtonStorefrontResponse> buttons,
        List<PlanStorefrontResponse> plans,
        Map<String, String> messages) {

    public static StorefrontResponse from(Storefront storefront,
                                          List<ButtonStorefrontResponse> buttons) {
        List<PlanStorefrontResponse> plans = storefront.activePlans().stream()
                .map(PlanStorefrontResponse::from)
                .toList();
        Map<String, String> messages = new LinkedHashMap<>();
        for (StoreMessage message : storefront.activeMessages()) {
            messages.put(message.event().name().toLowerCase(), message.text());
        }
        return new StorefrontResponse(StoreResponse.from(storefront.store()), buttons, plans, messages);
    }

    /** Botão do menu no storefront (payload tipado em claro ao dono da API key). */
    public record ButtonStorefrontResponse(
            UUID id,
            String label,
            StoreButtonAction action,
            Map<String, Object> payload,
            int position) {

        public static ButtonStorefrontResponse from(StoreButton button, Map<String, Object> payload) {
            return new ButtonStorefrontResponse(
                    button.id(), button.label(), button.action(), payload, button.position());
        }
    }

    /** Card do plano no storefront (sem payload de ações — nunca em claro). */
    public record PlanStorefrontResponse(
            UUID id,
            String name,
            String description,
            BigDecimal price,
            String imageUrl,
            String embedColor,
            boolean featured) {

        public static PlanStorefrontResponse from(Plan plan) {
            return new PlanStorefrontResponse(
                    plan.id(), plan.name(), plan.description(), plan.price().amount(),
                    plan.imageUrl(), plan.embedColor(), plan.featured());
        }
    }
}
