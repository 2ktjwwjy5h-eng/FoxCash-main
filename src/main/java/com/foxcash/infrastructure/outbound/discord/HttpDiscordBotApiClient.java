package com.foxcash.infrastructure.outbound.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.foxcash.domain.exception.DiscordApiException;
import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.infrastructure.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Adapter real da API REST do Discord v10 (Spec 74) — validação do token do bot
 * ({@code GET /users/@me}, header {@code Authorization: Bot <token>}) e registro
 * de comandos slash ({@code PUT /applications/{application_id}/commands}).
 * Timeouts 5s/10s (padrão dos adapters de PSP); falha → {@link DiscordApiException}.
 *
 * <p>A base URL é configurável ({@code foxcash.bots.discord-api-base-url}) para
 * o E2E apontar para um mock de contrato.
 */
@Component
public class HttpDiscordBotApiClient implements DiscordBotApi {

    private static final Logger log = LoggerFactory.getLogger(HttpDiscordBotApiClient.class);

    private final RestClient restClient;

    public HttpDiscordBotApiClient(BotProperties properties) {
        // JdkClientHttpRequestFactory (HttpClient do Java 21): suporta PATCH —
        // o SimpleClientHttpRequestFactory (HttpURLConnection) lança
        // ProtocolException em PATCH (necessário p/ updateProfile da Spec 78).
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getDiscordApiBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public DiscordUserInfo getMe(String token) {
        JsonNode node;
        try {
            node = restClient.get()
                    .uri("/users/@me")
                    .header(HttpHeaders.AUTHORIZATION, "Bot " + token)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw new DiscordApiException("Discord: timeout/conexao ao validar o token do bot", e);
        } catch (RestClientException e) {
            throw new DiscordApiException("Discord: falha HTTP ao validar o token do bot", e);
        }
        if (node == null) {
            throw new DiscordApiException("Discord: resposta vazia ao validar o token do bot");
        }
        String username = text(node, "username");
        String globalName = text(node, "global_name");
        String displayName = globalName != null && !globalName.isBlank() ? globalName : username;
        return new DiscordUserInfo(node.path("id").asLong(), username, displayName);
    }

    @Override
    public void putCommands(String applicationId, String token, List<SlashCommand> commands) {
        List<Map<String, String>> body = commands.stream()
                .map(c -> Map.of("name", c.name(), "description", c.description()))
                .toList();
        try {
            restClient.put()
                    .uri("/applications/{applicationId}/commands", applicationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bot " + token)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw new DiscordApiException("Discord: timeout/conexao ao registrar comandos slash", e);
        } catch (RestClientException e) {
            throw new DiscordApiException("Discord: falha HTTP ao registrar comandos slash", e);
        }
    }

    @Override
    public void updateProfile(String token, String description, byte[] avatar) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (description != null && !description.isBlank()) {
            // Campo "Sobre mim" do bot (PATCH /users/@me).
            body.put("bio", description);
        }
        if (avatar != null && avatar.length > 0) {
            // Discord aceita o avatar como data URI base64 (image/png ou image/jpeg).
            body.put("avatar", "data:image/png;base64," + Base64.getEncoder().encodeToString(avatar));
        }
        if (body.isEmpty()) {
            return;
        }
        try {
            restClient.patch()
                    .uri("/users/@me")
                    .header(HttpHeaders.AUTHORIZATION, "Bot " + token)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw new DiscordApiException("Discord: timeout/conexao ao atualizar o perfil do bot", e);
        } catch (RestClientException e) {
            throw new DiscordApiException("Discord: falha HTTP ao atualizar o perfil do bot", e);
        }
    }

    @Override
    public List<ChatInfo> listGuilds(String token) {
        JsonNode body;
        try {
            body = restClient.get()
                    .uri("/users/@me/guilds")
                    .header(HttpHeaders.AUTHORIZATION, "Bot " + token)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw new DiscordApiException("Discord: timeout/conexao ao listar guilds do bot", e);
        } catch (RestClientException e) {
            throw new DiscordApiException("Discord: falha HTTP ao listar guilds do bot", e);
        }
        if (body == null || !body.isArray()) {
            return List.of();
        }
        List<ChatInfo> guilds = new java.util.ArrayList<>();
        for (JsonNode guild : body) {
            String id = text(guild, "id");
            String name = text(guild, "name");
            if (id != null && !id.isBlank()) {
                guilds.add(new ChatInfo(id, name == null || name.isBlank() ? "Guild " + id : name));
            }
        }
        return guilds;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
