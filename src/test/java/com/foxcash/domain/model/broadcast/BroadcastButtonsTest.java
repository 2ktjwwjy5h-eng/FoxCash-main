package com.foxcash.domain.model.broadcast;

import com.foxcash.domain.model.storebot.BotPlatform;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do value object {@link BroadcastButtons} (Spec 79/D8): parse e
 * validação do {@code buttons_json} — estrutura {@code {rows: [...]}}, limites
 * por plataforma (Telegram ≤100 botões/≤8 por linha; Discord ≤5/≤5 por linha),
 * botão link exige URL válida (validador injetado — SSRF-safe) e callback exige
 * formato {@code bot:<comando>}.
 */
class BroadcastButtonsTest {

    /** Validador de URL de teste — aceita http(s), rejeita o resto. */
    private static final Function<String, String> URL_VALIDATOR = url -> {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("URL invalida");
        }
        return url;
    };

    @Test
    void parseValidGrid() {
        String json = """
                {"rows": [
                  [{"label": "Comprar", "url": "https://loja.example.com/produto"}],
                  [{"label": "Suporte", "callback": "bot:suporte"}, {"label": "FAQ", "url": "https://loja.example.com/faq"}]
                ]}
                """;

        BroadcastButtons buttons = BroadcastButtons.parse(json, BotPlatform.TELEGRAM, URL_VALIDATOR);

        assertThat(buttons.total()).isEqualTo(3);
        assertThat(buttons.rows()).hasSize(2);
        assertThat(buttons.rows().get(0)).hasSize(1);
        assertThat(buttons.rows().get(1)).hasSize(2);
        assertThat(buttons.rows().get(1).get(0).label()).isEqualTo("Suporte");
        assertThat(buttons.rows().get(1).get(0).callback()).isEqualTo("bot:suporte");
        assertThat(buttons.rows().get(1).get(1).url()).isEqualTo("https://loja.example.com/faq");
    }

    @Test
    void parsePreservesJsonForPersistence() {
        String json = "{\"rows\":[[{\"label\":\"Ok\",\"callback\":\"bot:ok\"}]]}";

        BroadcastButtons buttons = BroadcastButtons.parse(json, BotPlatform.TELEGRAM, URL_VALIDATOR);

        assertThat(buttons.json()).isEqualTo(json);
    }

    @Test
    void rejectsNullBlankOrMalformedJson() {
        assertThatThrownBy(() -> BroadcastButtons.parse(null, BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obrigatorio");

        assertThatThrownBy(() -> BroadcastButtons.parse("   ", BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obrigatorio");

        assertThatThrownBy(() -> BroadcastButtons.parse("{not json", BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON malformado");
    }

    @Test
    void rejectsMissingRows() {
        assertThatThrownBy(() -> BroadcastButtons.parse("{\"outros\": []}", BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");

        assertThatThrownBy(() -> BroadcastButtons.parse("[]", BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
    }

    @Test
    void rejectsEmptyRow() {
        String json = "{\"rows\":[[], [{\"label\":\"Ok\",\"callback\":\"bot:ok\"}]]}";

        assertThatThrownBy(() -> BroadcastButtons.parse(json, BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vazia");
    }

    @Test
    void rejectsMissingLabel() {
        String json = "{\"rows\":[[{\"url\":\"https://loja.example.com\"}]]}";

        assertThatThrownBy(() -> BroadcastButtons.parse(json, BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("label");
    }

    @Test
    void rejectsButtonWithBothOrNeitherUrlAndCallback() {
        String both = "{\"rows\":[[{\"label\":\"X\",\"url\":\"https://a.com\",\"callback\":\"bot:x\"}]]}";
        String neither = "{\"rows\":[[{\"label\":\"X\"}]]}";

        assertThatThrownBy(() -> BroadcastButtons.parse(both, BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exatamente um");

        assertThatThrownBy(() -> BroadcastButtons.parse(neither, BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exatamente um");
    }

    @Test
    void linkButtonRejectsInvalidUrlViaInjectedValidator() {
        String json = "{\"rows\":[[{\"label\":\"X\",\"url\":\"javascript:alert(1)\"}]]}";

        assertThatThrownBy(() -> BroadcastButtons.parse(json, BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL invalida");
    }

    @Test
    void callbackRequiresBotPrefix() {
        String json = "{\"rows\":[[{\"label\":\"X\",\"callback\":\"pagamento\"}]]}";

        assertThatThrownBy(() -> BroadcastButtons.parse(json, BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bot:<comando>");
    }

    @Test
    void telegramLimitsTotalButtonsAndPerRow() {
        // 9 botões numa linha → excede 8/linha.
        String nineInRow = "{\"rows\":[" + row(9) + "]}";
        assertThatThrownBy(() -> BroadcastButtons.parse(nineInRow, BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("linha");

        // 101 botões no total → excede 100.
        StringBuilder sb = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("[{\"label\":\"B").append(i).append("\",\"callback\":\"bot:x\"}]");
        }
        sb.append("]}");
        assertThatThrownBy(() -> BroadcastButtons.parse(sb.toString(), BotPlatform.TELEGRAM, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numero maximo");
    }

    @Test
    void telegramAcceptsExactlyEightPerRowAndHundredTotal() {
        String eightPerRow = "{\"rows\":[" + row(8) + "," + row(8) + "]}";
        BroadcastButtons buttons = BroadcastButtons.parse(eightPerRow, BotPlatform.TELEGRAM, URL_VALIDATOR);
        assertThat(buttons.total()).isEqualTo(16);
    }

    @Test
    void discordLimitsFiveButtonsTotal() {
        // 6 botões (1 por linha) → excede 5 do Discord.
        StringBuilder sb = new StringBuilder("{\"rows\":[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("[{\"label\":\"D").append(i).append("\",\"callback\":\"bot:x\"}]");
        }
        sb.append("]}");
        assertThatThrownBy(() -> BroadcastButtons.parse(sb.toString(), BotPlatform.DISCORD, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numero maximo");

        // 6 numa linha → excede o total de 5 do Discord (o check de total dispara
        // antes do check por linha — limite por linha também é 5).
        String sixInRow = "{\"rows\":[" + row(6) + "]}";
        assertThatThrownBy(() -> BroadcastButtons.parse(sixInRow, BotPlatform.DISCORD, URL_VALIDATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excedido");
    }

    @Test
    void restoreRehydratesWithoutRevalidation() {
        String json = "{\"rows\":[[{\"label\":\"Ok\",\"callback\":\"bot:ok\"}]]}";

        BroadcastButtons restored = BroadcastButtons.restore(json);

        assertThat(restored).isNotNull();
        assertThat(restored.total()).isEqualTo(1);
        assertThat(restored.json()).isEqualTo(json);
    }

    @Test
    void restoreNullJsonReturnsNull() {
        assertThat(BroadcastButtons.restore(null)).isNull();
        assertThat(BroadcastButtons.restore("  ")).isNull();
    }

    /** Gera uma linha com {@code n} botões de callback válidos. */
    private static String row(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"label\":\"B").append(i).append("\",\"callback\":\"bot:x\"}");
        }
        sb.append(']');
        return sb.toString();
    }
}
