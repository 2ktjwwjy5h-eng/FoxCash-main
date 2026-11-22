package com.foxcash.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.store.StoreButtonAction;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Validação/serialização do payload do botão do menu (Spec 50).
 *
 * <p>Cada {@link StoreButtonAction} exige um payload tipado:
 * <ul>
 *   <li>{@code BUY}/{@code CLOSE} — sem payload (null).</li>
 *   <li>{@code PREVIEW} — {@code {mediaUrls[]}} (URLs http(s) ≤500).</li>
 *   <li>{@code FAQ} — {@code {text}} (≤1000 caracteres).</li>
 *   <li>{@code SOCIAL}/{@code LINK} — {@code {url, label?}} (URL http(s) ≤500).</li>
 * </ul>
 * O payload é serializado em JSON (string) para ser **criptografado em repouso**
 * pela {@code CryptoConverter}.
 */
@Component
public class StoreButtonPayloadCodec {

    private static final int MAX_URL_LENGTH = 500;
    private static final int MAX_TEXT_LENGTH = 1000;
    private static final int MAX_MEDIA_URLS = 10;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public StoreButtonPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Valida o payload conforme o tipo e serializa em JSON (null para
     * BUY/CLOSE — sem payload).
     */
    public String validateAndEncode(StoreButtonAction action, Map<String, Object> payload) {
        if (action == null) {
            throw new IllegalArgumentException("action e obrigatoria no botao do menu");
        }
        switch (action) {
            case BUY, CLOSE -> {
                if (payload != null && !payload.isEmpty()) {
                    throw new IllegalArgumentException(
                            "botao " + action + " nao aceita payload");
                }
                return null;
            }
            case PREVIEW -> requirePreview(payload);
            case FAQ -> requireFaq(payload);
            case SOCIAL, LINK -> requireLink(payload);
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload invalido: " + e.getMessage());
        }
    }

    /** Lê o JSON (já decifrado na persistência) de volta em Map — para resposta. */
    public Map<String, Object> decode(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payloadJson, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao ler payload do botao do menu", e);
        }
    }

    private void requirePreview(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload de PREVIEW exige mediaUrls");
        }
        Object raw = payload.get("mediaUrls");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("payload de PREVIEW exige mediaUrls (nao vazio)");
        }
        if (list.size() > MAX_MEDIA_URLS) {
            throw new IllegalArgumentException(
                    "mediaUrls deve ter no maximo " + MAX_MEDIA_URLS + " itens");
        }
        for (Object item : list) {
            if (item == null || item.toString().isBlank() || item.toString().length() > MAX_URL_LENGTH
                    || !item.toString().startsWith("http")) {
                throw new IllegalArgumentException(
                        "cada mediaUrl deve ser uma url http(s) de ate " + MAX_URL_LENGTH + " caracteres");
            }
        }
    }

    private void requireFaq(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload de FAQ exige text");
        }
        Object text = payload.get("text");
        if (text == null || text.toString().isBlank()) {
            throw new IllegalArgumentException("payload de FAQ exige a chave \"text\"");
        }
        if (text.toString().length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text deve ter no maximo " + MAX_TEXT_LENGTH + " caracteres");
        }
    }

    private void requireLink(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload de " + "LINK/SOCIAL" + " exige url");
        }
        Object url = payload.get("url");
        if (url == null || url.toString().isBlank()) {
            throw new IllegalArgumentException("payload de LINK/SOCIAL exige a chave \"url\"");
        }
        if (url.toString().length() > MAX_URL_LENGTH || !url.toString().startsWith("http")) {
            throw new IllegalArgumentException(
                    "url deve ser uma url http(s) de ate " + MAX_URL_LENGTH + " caracteres");
        }
        Object label = payload.get("label");
        if (label != null && label.toString().length() > 80) {
            throw new IllegalArgumentException("label deve ter no maximo 80 caracteres");
        }
    }
}
