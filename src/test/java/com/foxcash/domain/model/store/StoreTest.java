package com.foxcash.domain.model.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Testes do modelo de dominio da loja do vendedor (Spec 48): criacao com
 * validacoes (nome, URLs, cor, redes sociais), restore e update parcial.
 */
class StoreTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    @Test
    void createSetsIdsAndTimestamps() {
        Store store = Store.create(MERCHANT_ID, "Bella Premium", null, null, "Conteudo exclusivo",
                List.of(new Store.SocialLink("Instagram", "https://instagram.com/bella")),
                "#9B59B6", "⭐", "Bella Premium VIP", "Feito com carinho", NOW);

        assertEquals(MERCHANT_ID, store.merchantId());
        assertEquals("Bella Premium", store.name());
        assertEquals("#9B59B6", store.color());
        assertEquals(1, store.socialLinks().size());
        assertEquals(NOW, store.createdAt());
        assertEquals(NOW, store.updatedAt());
    }

    @Test
    void createRequiresName() {
        assertThrows(IllegalArgumentException.class,
                () -> Store.create(MERCHANT_ID, null, null, null, null, null, null, null, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> Store.create(MERCHANT_ID, "   ", null, null, null, null, null, null, null, null, NOW));
    }

    @Test
    void createRejectsLongName() {
        assertThrows(IllegalArgumentException.class,
                () -> Store.create(MERCHANT_ID, "a".repeat(121), null, null, null, null, null, null, null, null, NOW));
    }

    @Test
    void createRejectsInvalidColor() {
        assertThrows(IllegalArgumentException.class,
                () -> Store.create(MERCHANT_ID, "Loja", null, null, null, null, "red", null, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> Store.create(MERCHANT_ID, "Loja", null, null, null, null, "#9B59", null, null, null, NOW));
    }

    @Test
    void createRejectsTooManySocialLinks() {
        List<Store.SocialLink> links = List.of(
                new Store.SocialLink("a", "https://a.com"),
                new Store.SocialLink("b", "https://b.com"),
                new Store.SocialLink("c", "https://c.com"),
                new Store.SocialLink("d", "https://d.com"),
                new Store.SocialLink("e", "https://e.com"),
                new Store.SocialLink("f", "https://f.com"),
                new Store.SocialLink("g", "https://g.com"),
                new Store.SocialLink("h", "https://h.com"),
                new Store.SocialLink("i", "https://i.com"),
                new Store.SocialLink("j", "https://j.com"),
                new Store.SocialLink("k", "https://k.com"));
        assertThrows(IllegalArgumentException.class,
                () -> Store.create(MERCHANT_ID, "Loja", null, null, null, links, null, null, null, null, NOW));
    }

    @Test
    void createRejectsSocialLinkWithoutHttpUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> Store.create(MERCHANT_ID, "Loja", null, null, null,
                        List.of(new Store.SocialLink("Instagram", "instagram.com/bella")),
                        null, null, null, null, NOW));
    }

    @Test
    void socialLinkRejectsNullOrBlankLabelAndUrl() {
        // Payload com item faltando label/url vira IllegalArgumentException
        // (400 invalid_argument) — nunca NullPointerException (500).
        assertThrows(IllegalArgumentException.class,
                () -> new Store.SocialLink(null, "https://x.com"));
        assertThrows(IllegalArgumentException.class,
                () -> new Store.SocialLink("   ", "https://x.com"));
        assertThrows(IllegalArgumentException.class,
                () -> new Store.SocialLink("X", null));
        assertThrows(IllegalArgumentException.class,
                () -> new Store.SocialLink("X", "   "));
    }

    @Test
    void updateChangesOnlyProvidedFieldsAndBumpsUpdatedAt() {
        Store store = Store.create(MERCHANT_ID, "Bella Premium", null, null, "Desc",
                null, "#9B59B6", null, null, null, NOW);

        store.update("Bella Nova", null, "https://cdn.example.com/banner.png", null, null,
                "#8E44AD", null, null, "Novo rodape", NOW.plusSeconds(60));

        assertEquals("Bella Nova", store.name());
        assertEquals("Desc", store.description());
        assertEquals("https://cdn.example.com/banner.png", store.bannerUrl());
        assertEquals("#8E44AD", store.color());
        assertEquals("Novo rodape", store.footer());
        assertEquals(NOW.plusSeconds(60), store.updatedAt());
    }

    @Test
    void updateValidatesMergedState() {
        Store store = Store.create(MERCHANT_ID, "Bella Premium", null, null, null,
                null, null, null, null, null, NOW);

        // Update com nome nulo mantém o nome válido — sem erro.
        store.update(null, null, null, null, null, null, null, null, null, NOW.plusSeconds(1));

        // Update com cor inválida falha mesmo com o resto válido.
        assertThrows(IllegalArgumentException.class,
                () -> store.update(null, null, null, null, null, "verde", null, null, null, NOW.plusSeconds(2)));
    }

    @Test
    void restoreKeepsAllFields() {
        UUID id = UUID.randomUUID();
        List<Store.SocialLink> links = List.of(new Store.SocialLink("X", "https://x.com"));
        Store store = Store.restore(id, MERCHANT_ID, "Loja", "https://logo", "https://banner",
                "Desc", links, "#112233", "🎁", "Titulo", "Rodape", NOW, NOW.plusSeconds(5));

        assertEquals(id, store.id());
        assertEquals(MERCHANT_ID, store.merchantId());
        assertEquals("https://logo", store.logoUrl());
        assertEquals(1, store.socialLinks().size());
        assertEquals(NOW.plusSeconds(5), store.updatedAt());
    }

    @Test
    void socialLinksAreDefensivelyCopied() {
        List<Store.SocialLink> links = new java.util.ArrayList<>(
                List.of(new Store.SocialLink("X", "https://x.com")));
        Store store = Store.create(MERCHANT_ID, "Loja", null, null, null, links, null, null, null, null, NOW);

        links.add(new Store.SocialLink("Y", "https://y.com"));

        assertEquals(1, store.socialLinks().size());
        assertEquals(List.of(new Store.SocialLink("X", "https://x.com")), store.socialLinks());
    }

    @Test
    void sameNameDiffersFromNullTitle() {
        Store a = Store.create(MERCHANT_ID, "Loja", null, null, null, null, null, null, null, null, NOW);
        Store b = Store.create(MERCHANT_ID, "Loja", null, null, null, null, null, null, "Titulo", null, NOW);
        assertNotEquals(a.title(), b.title());
    }

    @Test
    void createAcceptsExactBoundaryLengths() {
        // Mutation (ConditionalsBoundary > <-> >=): o limite exato é aceito.
        List<Store.SocialLink> links = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            links.add(new Store.SocialLink("l".repeat(40), "https://" + "x".repeat(492)));
        }
        Store store = Store.create(MERCHANT_ID, "a".repeat(120), "u".repeat(500), "b".repeat(500),
                "d".repeat(500), links, "#9B59B6", "e".repeat(10), "t".repeat(120), "f".repeat(500), NOW);

        assertEquals(120, store.name().length());
        assertEquals(500, store.logoUrl().length());
        assertEquals(500, store.bannerUrl().length());
        assertEquals(500, store.description().length());
        assertEquals(10, store.socialLinks().size());
        assertEquals(10, store.emoji().length());
        assertEquals(120, store.title().length());
        assertEquals(500, store.footer().length());
    }

    @Test
    void updateStoresEveryProvidedField() {
        // Mutation (NegateConditionals nos ternarios): cada campo fornecido
        // precisa ser gravado — logoUrl/emoji/title nao eram assertados.
        Store store = Store.create(MERCHANT_ID, "Loja", null, null, null, null, null, null, null, null, NOW);

        store.update("Nome Novo", "https://logo.png", "https://banner.png", "Desc",
                List.of(new Store.SocialLink("X", "https://x.com")), "#112233", "🖤", "Titulo", "Rodape",
                NOW.plusSeconds(60));

        assertEquals("https://logo.png", store.logoUrl());
        assertEquals("https://banner.png", store.bannerUrl());
        assertEquals("Desc", store.description());
        assertEquals(List.of(new Store.SocialLink("X", "https://x.com")), store.socialLinks());
        assertEquals("#112233", store.color());
        assertEquals("🖤", store.emoji());
        assertEquals("Titulo", store.title());
        assertEquals("Rodape", store.footer());
    }
}
