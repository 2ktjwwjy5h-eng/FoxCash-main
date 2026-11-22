package com.foxcash.domain.model.store;

/**
 * Tipos de ação do menu da loja (Spec 50) — o que acontece quando o comprador
 * toca no botão no bot (Spec 52). O vendedor monta o fluxo no painel; o bot
 * apenas renderiza.
 *
 * <ul>
 *   <li>{@code BUY} — abrir a lista de planos (Spec 49). Payload: nenhum.</li>
 *   <li>{@code PREVIEW} — abrir a prévia (mídias do plano/loja). Payload:
 *       {@code {mediaUrls[]}}.</li>
 *   <li>{@code FAQ} — abrir as mensagens de dúvidas. Payload: {@code {text}}.</li>
 *   <li>{@code SOCIAL} — abrir link (Instagram, "Falar comigo"...). Payload:
 *       {@code {url, label?}}.</li>
 *   <li>{@code LINK} — link externo qualquer (promoção, canal VIP). Payload:
 *       {@code {url, label?}}.</li>
 *   <li>{@code CLOSE} — encerrar o menu. Payload: nenhum.</li>
 * </ul>
 *
 * <p>Extensível: novo tipo = validação de payload (Spec 50) + renderização no
 * bot (Spec 52).
 */
public enum StoreButtonAction {

    /** Abrir a lista de planos (Spec 49). */
    BUY,

    /** Abrir a prévia (mídias do plano/loja). Payload: {mediaUrls[]}. */
    PREVIEW,

    /** Abrir as mensagens de dúvidas. Payload: {text}. */
    FAQ,

    /** Abrir link (Instagram, "Falar comigo"...). Payload: {url, label?}. */
    SOCIAL,

    /** Link externo qualquer (promoção, canal VIP). Payload: {url, label?}. */
    LINK,

    /** Encerrar o menu. */
    CLOSE
}
