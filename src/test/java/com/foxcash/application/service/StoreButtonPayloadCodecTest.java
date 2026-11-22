package com.foxcash.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.store.StoreButtonAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do codec de payload do botão do menu (Spec 50): validação por tipo
 * (BUY/CLOSE sem payload; PREVIEW com mediaUrls; FAQ com text; SOCIAL/LINK com
 * url) e limites (URLs ≤500, text ≤1000).
 */
class StoreButtonPayloadCodecTest {

    private StoreButtonPayloadCodec codec;

    @BeforeEach
    void setUp() {
        codec = new StoreButtonPayloadCodec(new ObjectMapper());
    }

    @Test
    void buyAndCloseHaveNoPayload() {
        assertThat(codec.validateAndEncode(StoreButtonAction.BUY, null)).isNull();
        assertThat(codec.validateAndEncode(StoreButtonAction.CLOSE, null)).isNull();
        assertThatThrownBy(() -> codec.validateAndEncode(StoreButtonAction.BUY, Map.of("x", "y")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void previewRequiresMediaUrls() {
        String encoded = codec.validateAndEncode(StoreButtonAction.PREVIEW,
                Map.of("mediaUrls", List.of("https://cdn.example.com/a.png")));
        assertThat(encoded).contains("mediaUrls");

        assertThatThrownBy(() -> codec.validateAndEncode(StoreButtonAction.PREVIEW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(StoreButtonAction.PREVIEW, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(StoreButtonAction.PREVIEW,
                Map.of("mediaUrls", List.of("not-a-url"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(StoreButtonAction.PREVIEW,
                Map.of("mediaUrls", List.of("https://x.com/" + "a".repeat(501)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void faqRequiresText() {
        String encoded = codec.validateAndEncode(StoreButtonAction.FAQ,
                Map.of("text", "Como recebo o acesso?"));
        assertThat(encoded).contains("text");

        assertThatThrownBy(() -> codec.validateAndEncode(StoreButtonAction.FAQ, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(StoreButtonAction.FAQ,
                Map.of("text", "a".repeat(1001))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void socialAndLinkRequireUrl() {
        String encoded = codec.validateAndEncode(StoreButtonAction.SOCIAL,
                Map.of("url", "https://instagram.com/loja", "label", "Instagram"));
        assertThat(encoded).contains("url").contains("Instagram");

        assertThat(codec.validateAndEncode(StoreButtonAction.LINK,
                Map.of("url", "https://t.me/+abc"))).isNotNull();

        assertThatThrownBy(() -> codec.validateAndEncode(StoreButtonAction.SOCIAL, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(StoreButtonAction.SOCIAL,
                Map.of("url", "instagram.com/sem-http")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.validateAndEncode(StoreButtonAction.LINK,
                Map.of("url", "https://x.com/" + "a".repeat(501))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeReturnsMapOrNull() {
        assertThat(codec.decode("{\"url\":\"https://x.com\"}")).containsEntry("url", "https://x.com");
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("   ")).isNull();
    }

    @Test
    void requiresAction() {
        assertThatThrownBy(() -> codec.validateAndEncode(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsExactBoundarySizes() {
        // L87/L92/L108/L121/L126: limites exatos são aceitos — os boundary
        // mutants (`>` → `>=`) rejeitariam 10 mediaUrls, url/text/label no
        // tamanho máximo.
        String url500 = "http://" + "a".repeat(493); // 7 + 493 = 500 chars
        Map<String, Object> preview = new java.util.LinkedHashMap<>();
        preview.put("mediaUrls", java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> url500).toList());
        assertThat(codec.validateAndEncode(StoreButtonAction.PREVIEW, preview)).isNotNull();

        assertThat(codec.validateAndEncode(StoreButtonAction.FAQ, Map.of("text", "t".repeat(1000))))
                .isNotNull();

        assertThat(codec.validateAndEncode(StoreButtonAction.LINK,
                Map.of("url", url500, "label", "l".repeat(80)))).isNotNull();
    }
}
