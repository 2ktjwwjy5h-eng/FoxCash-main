package com.foxcash.infrastructure.outbound.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foxcash.domain.exception.TelegramApiException;
import com.foxcash.domain.port.out.TelegramBotApi;
import com.foxcash.infrastructure.config.BotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Adapter real da Bot API do Telegram (Spec 74) — REST sobre
 * {@code https://api.telegram.org/bot{token}/{method}}. Timeouts 5s/10s (padrão
 * dos adapters de PSP, Spec 11/38); qualquer falha (token inválido,
 * {@code ok:false}, rede/HTTP) vira {@link TelegramApiException} — a integração
 * marca o bot {@code ERROR} com auditoria.
 *
 * <p>A base URL é configurável ({@code foxcash.bots.telegram-api-base-url}) para
 * o E2E apontar para um mock de contrato (T2.5/T5.3).
 */
@Component
public class HttpTelegramBotApiClient implements TelegramBotApi {

    private static final Logger log = LoggerFactory.getLogger(HttpTelegramBotApiClient.class);

    /** Só usado para extrair o {@code description} do corpo de erro da Bot API. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final BotProperties properties;

    public HttpTelegramBotApiClient(BotProperties properties) {
        // Timeouts (padrão Spec 11/38): Connect 5s / Read 10s — uma lentidão da
        // API do Telegram não segura a thread da integração indefinidamente.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getTelegramApiBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.properties = properties;
    }

    @Override
    public TelegramUserInfo getMe(String token) {
        JsonNode result = callGet(token, "getMe");
        String username = text(result, "username");
        String firstName = text(result, "first_name");
        String lastName = text(result, "last_name");
        String displayName = firstName == null || firstName.isBlank()
                ? username
                : (lastName == null || lastName.isBlank() ? firstName : firstName + " " + lastName);
        return new TelegramUserInfo(result.path("id").asLong(), username, displayName);
    }

    @Override
    public void setWebhook(String token, String url, String secretToken) {
        callPost(token, "setWebhook", Map.of(
                "url", url,
                "secret_token", secretToken,
                "drop_pending_updates", true));
    }

    @Override
    public void deleteWebhook(String token) {
        callPost(token, "deleteWebhook", Map.of("drop_pending_updates", true));
    }

    @Override
    public void setMyDescription(String token, String description) {
        callPost(token, "setMyDescription", Map.of("description", description == null ? "" : description));
    }

    @Override
    public void setMyShortDescription(String token, String shortDescription) {
        callPost(token, "setMyShortDescription",
                Map.of("short_description", shortDescription == null ? "" : shortDescription));
    }

    @Override
    public void setMyPhoto(String token, byte[] photo, String filename) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("photo", new ByteArrayResource(photo) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        requireOk(execute(() -> restClient.post()
                .uri("/bot{token}/setMyPhoto", token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .body(JsonNode.class)), "setMyPhoto");
    }

    @Override
    public void setMyCommands(String token, List<BotCommand> commands) {
        List<Map<String, String>> body = commands.stream()
                .map(c -> Map.of("command", c.command(), "description", c.description()))
                .toList();
        callPost(token, "setMyCommands", Map.of("commands", body));
    }

    @Override
    public TelegramWebhookInfo getWebhookInfo(String token) {
        JsonNode result = callGet(token, "getWebhookInfo");
        return new TelegramWebhookInfo(text(result, "url"), result.path("pending_update_count").asInt(0));
    }

    @Override
    public List<ChatInfo> listChats(String token) {
        // Fonte: getUpdates (dedup por chat) + getChat para título/tipo. Com
        // webhook ativo (produção) o getUpdates devolve 409 → lista vazia (o
        // renderizador reporta os chats via API). Best-effort: nunca lança.
        try {
            JsonNode updates = requireOk(execute(() -> restClient.post()
                    .uri("/bot{token}/getUpdates", token)
                    .body(Map.of("timeout", 0, "allowed_updates", List.of("message", "my_chat_member")))
                    .retrieve()
                    .body(JsonNode.class)), "getUpdates");
            return collectChats(updates);
        } catch (TelegramApiException e) {
            log.warn("Telegram: getUpdates indisponivel (webhook ativo?) — listChats vazio: {}",
                    e.getMessage());
            return List.of();
        }
    }

    private static List<ChatInfo> collectChats(JsonNode updates) {
        Map<String, JsonNode> byChat = new java.util.LinkedHashMap<>();
        if (updates != null && updates.isArray()) {
            for (JsonNode update : updates) {
                JsonNode chat = chatFrom(update);
                if (chat != null && !chat.path("id").isMissingNode()) {
                    byChat.putIfAbsent(String.valueOf(chat.path("id").asLong()), chat);
                }
            }
        }
        List<ChatInfo> chats = new java.util.ArrayList<>();
        for (JsonNode chat : byChat.values()) {
            // Apenas canais/grupos (supergroup/channel/group) — DMs não são
            // destino de broadcast (o envio por bot individual é outra feature).
            String type = text(chat, "type");
            if (type == null || type.equals("private")) {
                continue;
            }
            String title = text(chat, "title");
            if (title == null || title.isBlank()) {
                title = text(chat, "username");
            }
            chats.add(new ChatInfo(String.valueOf(chat.path("id").asLong()),
                    title == null ? "Chat " + chat.path("id").asLong() : title, type));
        }
        return chats;
    }

    private static JsonNode chatFrom(JsonNode update) {
        JsonNode message = update.path("message");
        if (!message.isMissingNode() && message.has("chat")) {
            return message.path("chat");
        }
        JsonNode myChatMember = update.path("my_chat_member");
        if (!myChatMember.isMissingNode() && myChatMember.has("chat")) {
            return myChatMember.path("chat");
        }
        return null;
    }

    @Override
    public String sendMedia(String token, String mediaType, byte[] bytes, String filename) {
        String method = switch (mediaType == null ? "" : mediaType.toLowerCase()) {
            case "image" -> "sendPhoto";
            case "video" -> "sendVideo";
            case "audio" -> "sendAudio";
            default -> throw new TelegramApiException(
                    "Telegram: media_type invalido para CDN: " + mediaType);
        };
        String cdnChannel = properties.getTelegramCdnChannelId();
        if (cdnChannel == null || cdnChannel.isBlank()) {
            throw new TelegramApiException(
                    "Telegram: canal CDN nao configurado (foxcash.bots.telegram-cdn-channel-id)");
        }
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("chat_id", cdnChannel);
        builder.part(method.equals("sendPhoto") ? "photo" : method.equals("sendVideo") ? "video" : "audio",
                new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        JsonNode result = requireOk(execute(() -> restClient.post()
                .uri("/bot{token}/{method}", token, method)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .body(JsonNode.class)), method);
        // O file_id do documento enviado ao canal CDN é reusado nos disparos
        // (sendPhoto devolve photo[].file_id; sendVideo/sendAudio → file_id).
        String fileId = fileIdFrom(result);
        if (fileId == null || fileId.isBlank()) {
            throw new TelegramApiException(
                    "Telegram: " + method + " nao devolveu file_id (canal CDN)");
        }
        return fileId;
    }

    private static String fileIdFrom(JsonNode result) {
        JsonNode photo = result.path("photo");
        if (photo.isArray() && photo.size() > 0) {
            return text(photo.get(photo.size() - 1), "file_id");
        }
        return text(result, "file_id");
    }

    private JsonNode callGet(String token, String method) {
        return requireOk(execute(() -> restClient.get()
                .uri("/bot{token}/{method}", token, method)
                .retrieve()
                .body(JsonNode.class)), method);
    }

    @Override
    public void sendMessage(String token, String chatId, String text) {
        JsonNode node = execute(() -> restClient.post()
                .uri("/bot{token}/sendMessage", token)
                .body(Map.of("chat_id", chatId, "text", text == null ? "" : text))
                .retrieve()
                .body(JsonNode.class));
        // Detecção de bloqueio (Spec 83 D6): 403 "bot was blocked by the user"
        // → LeadBlockedException (o CRM marca o lead BLOQUEADO + auditoria).
        int errorCode = node == null ? 0 : node.path("error_code").asInt(0);
        String description = node == null ? null : text(node, "description");
        if (node == null || !node.path("ok").asBoolean(false)) {
            log.warn("Telegram: sendMessage rejeitado (error_code={}): {}", errorCode, description);
            if (errorCode == 403 && description != null
                    && description.toLowerCase().contains("blocked by the user")) {
                throw new com.foxcash.domain.exception.LeadBlockedException(
                        "Usuario bloqueou o bot (403)");
            }
            throw new TelegramApiException("Telegram: sendMessage rejeitado pela Bot API"
                    + (description == null ? "" : ": " + description));
        }
    }

    private JsonNode callPost(String token, String method, Object body) {
        return requireOk(execute(() -> restClient.post()
                .uri("/bot{token}/{method}", token, method)
                .body(body)
                .retrieve()
                .body(JsonNode.class)), method);
    }

    private JsonNode execute(java.util.function.Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw new TelegramApiException("Telegram: timeout/conexao ao chamar a Bot API", e);
        } catch (RestClientResponseException e) {
            // A Bot API devolve {"ok":false,"error_code":...,"description":"..."} em
            // 4xx/5xx — expor a descrição real (ex.: Unauthorized,
            // PHOTO_INVALID_DIMENSIONS) em vez de esconder o motivo atrás de
            // "falha HTTP ao chamar a Bot API" (o lojista precisa saber por que
            // o token/setup falhou — Specs 77/78).
            String detail = telegramErrorDetail(e.getResponseBodyAsString(), e.getStatusCode().value());
            throw new TelegramApiException("Telegram: falha HTTP ao chamar a Bot API"
                    + (detail == null ? "" : ": " + detail), e);
        } catch (RestClientException e) {
            throw new TelegramApiException("Telegram: falha HTTP ao chamar a Bot API", e);
        }
    }

    /**
     * Extrai a descrição do erro da Bot API do corpo JSON da resposta
     * ({@code {"ok":false,"error_code":401,"description":"Unauthorized"}}). Sem
     * {@code description}, cai para {@code HTTP <status>}; corpo ilegível →
     * {@code null} (mantém a mensagem genérica).
     */
    private static String telegramErrorDetail(String body, int statusCode) {
        if (body == null || body.isBlank()) {
            return statusCode > 0 ? "HTTP " + statusCode : null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            String description = text(node, "description");
            if (description != null && !description.isBlank()) {
                return description;
            }
            int errorCode = node.path("error_code").asInt(0);
            return errorCode > 0 ? "HTTP " + errorCode : "HTTP " + statusCode;
        } catch (Exception e) {
            return statusCode > 0 ? "HTTP " + statusCode : null;
        }
    }

    private JsonNode requireOk(JsonNode node, String method) {
        if (node == null || !node.path("ok").asBoolean(false)) {
            String description = node == null ? null : text(node, "description");
            log.warn("Telegram: metodo {} rejeitado pela Bot API: {}", method, description);
            throw new TelegramApiException("Telegram: " + method + " rejeitado pela Bot API"
                    + (description == null ? "" : ": " + description));
        }
        return node.path("result");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
