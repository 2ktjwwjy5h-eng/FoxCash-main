package com.foxcash.domain.model.plan;

/**
 * Tipos de ação de entrega (Spec 49) — o que acontece quando o pagamento do
 * plano é aprovado. Extensível: novo tipo = validação de payload + adapter no
 * bot (Spec 52).
 */
public enum DeliveryActionType {

    /** Dar cargo no Discord. Payload: {roleId}. */
    GRANT_ROLE,

    /** Enviar mensagem de boas-vindas. Payload: {text}. */
    SEND_MESSAGE,

    /** Enviar link permanente. Payload: {url, label?}. */
    SEND_LINK,

    /** Adicionar ao grupo do Telegram. Payload: {groupId}. */
    ADD_TO_GROUP,

    /** Enviar arquivo. Payload: {fileUrl, filename?}. */
    SEND_FILE
}
