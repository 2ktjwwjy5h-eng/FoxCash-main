package com.foxcash.domain.model.store;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Botão do menu da loja (Spec 50) — a configuração do fluxo que o vendedor
 * monta no painel e o bot renderiza (Spec 52). Cada botão tem uma {@code action}
 * (BUY/PREVIEW/FAQ/SOCIAL/LINK/CLOSE), um {@code label} visível, um
 * {@code payload} JSON tipado (validado por tipo na camada de aplicação e
 * **criptografado em repouso** — nunca em claro no banco) e uma {@code position}
 * no menu (0..N, único por loja).
 */
public class StoreButton {

    private static final int MAX_LABEL = 80;
    private static final int MAX_ACTION_LENGTH = 30;

    private final UUID id;
    private final UUID storeId;
    private String label;
    private StoreButtonAction action;
    private String payload;
    private int position;
    private boolean active;
    private final Instant createdAt;

    private StoreButton(UUID id, UUID storeId, String label, StoreButtonAction action,
                        String payload, int position, boolean active, Instant createdAt) {
        this.id = id;
        this.storeId = storeId;
        this.label = label;
        this.action = action;
        this.payload = payload;
        this.position = position;
        this.active = active;
        this.createdAt = createdAt;
    }

    /** Cria um botão do menu (validações aplicadas; ativo por padrão). */
    public static StoreButton create(UUID storeId, String label, StoreButtonAction action,
                                     String payload, int position, Instant now) {
        Objects.requireNonNull(storeId, "storeId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        validate(label, action, payload, position);
        return new StoreButton(UUID.randomUUID(), storeId, label, action, payload,
                position, true, now);
    }

    /** Restaura do banco (sem validações — o dado persistido é confiável). */
    public static StoreButton restore(UUID id, UUID storeId, String label, StoreButtonAction action,
                                      String payload, int position, boolean active, Instant createdAt) {
        return new StoreButton(id, storeId, label, action, payload, position, active, createdAt);
    }

    /**
     * Atualiza os campos informados (não-nulos) e valida o resultado.
     * {@code null} em {@code label}/{@code action}/{@code payload} = manter o
     * atual; {@code null} em {@code active} = manter. A reordenação usa
     * {@link #moveTo(int)} (posição é tratada pelo serviço, não pelo PATCH).
     */
    public void update(String label, StoreButtonAction action, String payload, Boolean active) {
        String newLabel = label != null ? label : this.label;
        StoreButtonAction newAction = action != null ? action : this.action;
        String newPayload = payload != null ? payload : this.payload;
        boolean newActive = active != null ? active : this.active;
        validate(newLabel, newAction, newPayload, this.position);
        this.label = newLabel;
        this.action = newAction;
        this.payload = newPayload;
        this.active = newActive;
    }

    /** Ativa/desativa o botão (storefront só devolve ativos). */
    public void setActive(boolean active) {
        this.active = active;
    }

    /** Reposiciona o botão no menu (reordenação — o serviço recalcula 0..N). */
    public void moveTo(int position) {
        if (position < 0) {
            throw new IllegalArgumentException("position deve ser >= 0");
        }
        this.position = position;
    }

    private static void validate(String label, StoreButtonAction action, String payload, int position) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label e obrigatorio no botao do menu");
        }
        if (label.length() > MAX_LABEL) {
            throw new IllegalArgumentException("label deve ter no maximo " + MAX_LABEL + " caracteres");
        }
        if (action == null) {
            throw new IllegalArgumentException("action e obrigatoria no botao do menu");
        }
        if (action.name().length() > MAX_ACTION_LENGTH) {
            throw new IllegalArgumentException("action deve ter no maximo " + MAX_ACTION_LENGTH + " caracteres");
        }
        // O payload é obrigatório/validado por tipo na camada de aplicação
        // (StoreButtonPayloadCodec) — aqui apenas o enquadramento básico.
        if (position < 0) {
            throw new IllegalArgumentException("position deve ser >= 0");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public String label() {
        return label;
    }

    public StoreButtonAction action() {
        return action;
    }

    /** JSON tipado — em claro apenas em memória; criptografado em repouso. */
    public String payload() {
        return payload;
    }

    public int position() {
        return position;
    }

    public boolean active() {
        return active;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
