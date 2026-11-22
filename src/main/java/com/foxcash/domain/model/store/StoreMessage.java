package com.foxcash.domain.model.store;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mensagem automática da loja por etapa da venda (Spec 51) — o texto que o
 * vendedor edita no painel (upsert por {@code event}: 1 mensagem por etapa,
 * unique {@code (store_id, event)}) e o bot renderiza no fluxo (Spec 52). O
 * {@code text} pode conter link privado — nunca vai em claro para a trilha de
 * auditoria/logs (a atividade registra apenas o {@code event}).
 */
public class StoreMessage {

    public static final int MAX_TEXT = 1000;

    private final UUID id;
    private final UUID storeId;
    private final StoreMessageEvent event;
    private String text;
    private boolean active;
    private final Instant createdAt;
    private Instant updatedAt;

    private StoreMessage(UUID id, UUID storeId, StoreMessageEvent event, String text,
                         boolean active, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.storeId = storeId;
        this.event = event;
        this.text = text;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Cria a mensagem da etapa (validações aplicadas; ativa por padrão). */
    public static StoreMessage create(UUID storeId, StoreMessageEvent event, String text, Instant now) {
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        validate(event, text);
        return new StoreMessage(UUID.randomUUID(), storeId, event, text, true, now, now);
    }

    /** Restaura do banco (sem validações — o dado persistido é confiável). */
    public static StoreMessage restore(UUID id, UUID storeId, StoreMessageEvent event, String text,
                                       boolean active, Instant createdAt, Instant updatedAt) {
        return new StoreMessage(id, storeId, event, text, active, createdAt, updatedAt);
    }

    /**
     * Atualiza os campos informados (não-nulos) e valida o resultado.
     * {@code null} em {@code text}/{@code active} = manter o atual. O
     * {@code event} é imutável (definido na criação — o upsert/PATCH não o
     * altera). Bump em {@code updatedAt}.
     */
    public void update(String text, Boolean active, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        String newText = text != null ? text : this.text;
        boolean newActive = active != null ? active : this.active;
        validate(this.event, newText);
        this.text = newText;
        this.active = newActive;
        this.updatedAt = now;
    }

    /** Ativa/desativa a mensagem (storefront só devolve ativas). */
    public void setActive(boolean active) {
        this.active = active;
    }

    private static void validate(StoreMessageEvent event, String text) {
        if (event == null) {
            throw new IllegalArgumentException("event e obrigatorio na mensagem da loja");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text e obrigatorio na mensagem da loja");
        }
        if (text.length() > MAX_TEXT) {
            throw new IllegalArgumentException("text deve ter no maximo " + MAX_TEXT + " caracteres");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public StoreMessageEvent event() {
        return event;
    }

    /** Texto em claro apenas em memória/resposta ao dono — nunca em logs/trilha. */
    public String text() {
        return text;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
