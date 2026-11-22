package com.foxcash.domain.model.broadcast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.model.storebot.BotPlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Grade de botões inline do broadcast (Spec 79/D8) — value object que faz o
 * parse e a validação do {@code buttons_json}: estrutura
 * {@code {"rows": [[{label, url?|callback?}], ...]}} (linhas de botões, padrão
 * dos teclados inline do Telegram/Discord).
 *
 * <p><b>Jackson aqui é intencional</b> — a validação DO JSON é
 * responsabilidade deste value object (D8), e o Jackson já é dependência de
 * compilação do módulo (spring-boot-starter-web). O que NÃO entra no domínio é
 * a infraestrutura de segurança: a URL do botão {@code link} é validada por uma
 * {@code Function} injetada pela aplicação (o chamador passa
 * {@code url -> WebhookUrlValidator.validate(url, false)} — SSRF-safe, Spec 35),
 * preservando a fronteira hexagonal.
 *
 * <p>Limites por plataforma (D8): Telegram ≤100 botões, ≤8 por linha;
 * Discord ≤5 botões (total), ≤5 por linha. Botão {@code link} exige {@code url}
 * válida; botão {@code callback} exige {@code callback} no formato
 * {@code bot:<comando>} (resolvido pelo bot).
 */
public final class BroadcastButtons {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_LABEL = 80;
    private static final int TELEGRAM_MAX_BUTTONS = 100;
    private static final int TELEGRAM_MAX_PER_ROW = 8;
    private static final int DISCORD_MAX_BUTTONS = 5;
    private static final int DISCORD_MAX_PER_ROW = 5;
    private static final Pattern CALLBACK_PATTERN = Pattern.compile("^bot:.+");

    private final List<List<Button>> rows;
    private final String json; // JSON canônico (persistido em buttons_json JSONB)

    private BroadcastButtons(List<List<Button>> rows, String json) {
        this.rows = rows;
        this.json = json;
    }

    /**
     * Faz o parse e valida o {@code buttons_json} conforme a plataforma.
     *
     * @param json         o JSON cru do {@code buttons_json} (não-nulo/vazio).
     * @param platform     plataforma do bot (Telegram/Discord) — define os limites.
     * @param urlValidator função de validação de URL injetada (SSRF-safe — a
     *                     aplicação passa {@code WebhookUrlValidator.validate(url,
     *                     false)}); chamada para cada botão {@code link}.
     * @throws IllegalArgumentException quando a estrutura, os limites ou um botão
     *                                  violam o contrato (400 {@code invalid_argument}).
     */
    public static BroadcastButtons parse(String json, BotPlatform platform,
                                         Function<String, String> urlValidator) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("buttons_json e obrigatorio");
        }
        if (platform == null) {
            throw new IllegalArgumentException("platform e obrigatoria para validar os botoes");
        }
        if (urlValidator == null) {
            throw new IllegalArgumentException("urlValidator e obrigatorio (SSRF-safe)");
        }
        JsonNode root = readTree(json);
        if (root == null || !root.isObject() || !root.has("rows") || !root.get("rows").isArray()) {
            throw new IllegalArgumentException(
                    "buttons_json deve ter a estrutura {\"rows\": [[{label, url?|callback?}]]}");
        }
        List<List<Button>> rows = new ArrayList<>();
        int total = 0;
        for (JsonNode rowNode : root.get("rows")) {
            if (!rowNode.isArray()) {
                throw new IllegalArgumentException("cada linha de buttons_json deve ser um array de botoes");
            }
            List<Button> row = new ArrayList<>();
            for (JsonNode buttonNode : rowNode) {
                Button button = parseButton(buttonNode, urlValidator);
                row.add(button);
                total++;
            }
            if (row.isEmpty()) {
                throw new IllegalArgumentException("nenhuma linha de buttons_json pode ficar vazia");
            }
            rows.add(row);
        }
        validateLimits(rows, total, platform);
        return new BroadcastButtons(rows, json);
    }

    /**
     * Restaura do banco (sem revalidar URL/limites — o dado persistido é
     * confiável; o JSONB já passou pela validação na escrita). Apenas o parse
     * estrutural é refeito para reconstruir o value object.
     */
    public static BroadcastButtons restore(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root = readTree(json);
        if (root == null || !root.isObject() || !root.has("rows") || !root.get("rows").isArray()) {
            throw new IllegalStateException("buttons_json persistido invalido");
        }
        List<List<Button>> rows = new ArrayList<>();
        for (JsonNode rowNode : root.get("rows")) {
            List<Button> row = new ArrayList<>();
            for (JsonNode buttonNode : rowNode) {
                row.add(parseButton(buttonNode, url -> url)); // sem validação SSRF no restore
            }
            rows.add(row);
        }
        return new BroadcastButtons(rows, json);
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("buttons_json invalido (JSON malformado)");
        }
    }

    private static Button parseButton(JsonNode node, Function<String, String> urlValidator) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("cada botao de buttons_json deve ser um objeto");
        }
        JsonNode labelNode = node.get("label");
        if (labelNode == null || labelNode.asText().isBlank()) {
            throw new IllegalArgumentException("label e obrigatorio em cada botao");
        }
        String label = labelNode.asText();
        if (label.length() > MAX_LABEL) {
            throw new IllegalArgumentException("label deve ter no maximo " + MAX_LABEL + " caracteres");
        }
        JsonNode urlNode = node.get("url");
        JsonNode callbackNode = node.get("callback");
        boolean hasUrl = urlNode != null && !urlNode.asText().isBlank();
        boolean hasCallback = callbackNode != null && !callbackNode.asText().isBlank();
        if (hasUrl == hasCallback) {
            throw new IllegalArgumentException("cada botao deve ter exatamente um de url (link) ou callback");
        }
        if (hasUrl) {
            // Validação SSRF-safe injetada (a aplicação passa WebhookUrlValidator).
            String url = urlValidator.apply(urlNode.asText().trim());
            return new Button(label, url, null);
        }
        String callback = callbackNode.asText().trim();
        if (!CALLBACK_PATTERN.matcher(callback).matches()) {
            throw new IllegalArgumentException(
                    "callback deve estar no formato bot:<comando> (resolvido pelo bot)");
        }
        return new Button(label, null, callback);
    }

    private static void validateLimits(List<List<Button>> rows, int total, BotPlatform platform) {
        int maxButtons = platform == BotPlatform.TELEGRAM ? TELEGRAM_MAX_BUTTONS : DISCORD_MAX_BUTTONS;
        int maxPerRow = platform == BotPlatform.TELEGRAM ? TELEGRAM_MAX_PER_ROW : DISCORD_MAX_PER_ROW;
        if (total > maxButtons) {
            throw new IllegalArgumentException(
                    "numero maximo de botoes excedido (" + maxButtons + " na plataforma " + platform + ")");
        }
        for (List<Button> row : rows) {
            if (row.size() > maxPerRow) {
                throw new IllegalArgumentException(
                        "linha com mais de " + maxPerRow + " botoes (limite da plataforma " + platform + ")");
            }
        }
    }

    public List<List<Button>> rows() {
        return rows;
    }

    /** JSON canônico validado (persistido em {@code buttons_json}). */
    public String json() {
        return json;
    }

    /** Total de botões da grade. */
    public int total() {
        return rows.stream().mapToInt(List::size).sum();
    }

    /** Botão inline — {@code url} XOR {@code callback} (nunca ambos). */
    public record Button(String label, String url, String callback) {
    }
}
