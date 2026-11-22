package com.foxcash.infrastructure.security;

import com.foxcash.domain.model.merchant.Merchant;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Resolve o lojista autenticado no request atual (Spec 65 — Fase 1).
 *
 * <p>Os filtros de autenticacao ({@link ApiKeyAuthFilter} e
 * {@link MerchantPortalAuthFilter}) ja carregam o {@link Merchant} do banco
 * para validar o status (Spec 12); este resolver expoe a MESMA entidade aos
 * services do caminho do charge — eliminando o {@code findById} redundante que
 * o service fazia depois do filtro (capacity-model F1: ~5-6 SELECTs de auth por
 * venda). Fora de um request HTTP (jobs, testes, chamadas internas) retorna
 * vazio e o service usa o fallback ao repositorio.
 *
 * <p>Seguranca: a entidade so entra no request se o filtro ja validou status
 * {@code ACTIVE} + escopo; o service continua re-verificando a invariante de
 * dominio. Estado por-request (atributo do {@code HttpServletRequest} — nao
 * compartilhado entre threads), topologia multi-instancia inalterada (Spec 61).
 */
@Component
public class AuthenticatedMerchantResolver {

    public static final String MERCHANT_ATTRIBUTE =
            AuthenticatedMerchantResolver.class.getName() + ".AUTHENTICATED_MERCHANT";

    /** Merchant resolvido pelos filtros de auth no request atual (vazio fora de request). */
    public Optional<Merchant> currentMerchant() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet && servlet.getRequest() != null) {
            Object merchant = servlet.getRequest().getAttribute(MERCHANT_ATTRIBUTE);
            return merchant instanceof Merchant m ? Optional.of(m) : Optional.empty();
        }
        return Optional.empty();
    }
}
