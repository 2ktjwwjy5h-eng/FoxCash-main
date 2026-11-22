package com.foxcash.infrastructure.outbound.telegram;

import com.foxcash.domain.exception.TelegramApiException;
import com.foxcash.domain.port.out.TelegramBotApi;
import com.foxcash.infrastructure.config.BotProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes do {@link HttpTelegramBotApiClient} (Spec 74/T2.5) — mock de contrato
 * da Bot API do Telegram contra um MockWebServer: getMe (identidade), setWebhook
 * (URL + secret_token no corpo), deleteWebhook, rejeição {@code ok:false} →
 * {@link TelegramApiException}.
 */
class HttpTelegramBotApiClientTest {

    private static final String TOKEN = "123456:ABC-secret";

    private MockWebServer server;
    private HttpTelegramBotApiClient client;
    private BotProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new BotProperties();
        properties.setTelegramApiBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        client = new HttpTelegramBotApiClient(properties);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void getMeReturnsIdentity() throws Exception {
        server.enqueue(jsonResponse(
                "{\"ok\":true,\"result\":{\"id\":123,\"is_bot\":true,\"first_name\":\"Bot Vendas\","
                        + "\"username\":\"vendasbot\",\"can_join_groups\":true}}"));

        TelegramBotApi.TelegramUserInfo me = client.getMe(TOKEN);

        assertThat(me.id()).isEqualTo(123L);
        assertThat(me.username()).isEqualTo("vendasbot");
        assertThat(me.displayName()).isEqualTo("Bot Vendas");

        RecordedRequest request = server.takeRequest();
        // O `:` do token é percent-encoded pelo cliente HTTP (correto p/ path).
        assertThat(request.getPath()).isEqualTo("/bot123456%3AABC-secret/getMe");
    }

    @Test
    void setWebhookSendsUrlAndSecretToken() throws Exception {
        server.enqueue(jsonResponse("{\"ok\":true,\"result\":true,\"description\":\"Webhook was set\"}"));

        client.setWebhook(TOKEN, "https://api.foxcash.example.com/webhooks/telegram/abc/secret123", "secret123");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/bot123456%3AABC-secret/setWebhook");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("https://api.foxcash.example.com/webhooks/telegram/abc/secret123");
        assertThat(body).contains("\"secret_token\":\"secret123\"");
        assertThat(body).contains("\"drop_pending_updates\":true");
    }

    @Test
    void deleteWebhookPosts() throws Exception {
        server.enqueue(jsonResponse("{\"ok\":true,\"result\":true,\"description\":\"Webhook was deleted\"}"));

        client.deleteWebhook(TOKEN);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/bot123456%3AABC-secret/deleteWebhook");
    }

    @Test
    void setMyCommandsSendsCommands() throws Exception {
        server.enqueue(jsonResponse("{\"ok\":true,\"result\":true}"));

        client.setMyCommands(TOKEN, List.of(new TelegramBotApi.BotCommand("comprar", "Compre um plano")));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/bot123456%3AABC-secret/setMyCommands");
        assertThat(request.getBody().readUtf8()).contains("\"command\":\"comprar\"");
    }

    @Test
    void setMyPhotoSendsMultipartWithFile() throws Exception {
        server.enqueue(jsonResponse("{\"ok\":true,\"result\":true}"));

        byte[] photo = new byte[]{1, 2, 3, 4};
        client.setMyPhoto(TOKEN, photo, "bot-photo.jpg");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/bot123456%3AABC-secret/setMyPhoto");
        // multipart/form-data com boundary e o conteúdo binário do arquivo.
        assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("name=\"photo\"");
        assertThat(body).contains("filename=\"bot-photo.jpg\"");
        assertThat(body).contains(new String(new byte[]{1, 2, 3, 4}, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    @Test
    void getWebhookInfoReturnsUrl() throws Exception {
        server.enqueue(jsonResponse(
                "{\"ok\":true,\"result\":{\"url\":\"https://x/webhooks/telegram/a/s\",\"pending_update_count\":3}}"));

        TelegramBotApi.TelegramWebhookInfo info = client.getWebhookInfo(TOKEN);

        assertThat(info.url()).isEqualTo("https://x/webhooks/telegram/a/s");
        assertThat(info.pendingUpdateCount()).isEqualTo(3);
    }

    @Test
    void okFalseThrowsTelegramApiException() {
        server.enqueue(jsonResponse(
                "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}"));

        assertThatThrownBy(() -> client.getMe(TOKEN))
                .isInstanceOf(TelegramApiException.class)
                .hasMessageContaining("Unauthorized");
    }

    @Test
    void httpErrorThrowsTelegramApiException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"ok\":false}"));

        assertThatThrownBy(() -> client.getMe(TOKEN))
                .isInstanceOf(TelegramApiException.class);
    }

    @Test
    void httpErrorSurfacesTelegramDescription() {
        // 400 com o corpo de erro real da Bot API — o motivo (ex. foto inválida
        // no setup, token errado no validate) não pode ficar escondido atrás de
        // "falha HTTP ao chamar a Bot API" (Specs 77/78).
        server.enqueue(new MockResponse().setResponseCode(400).setBody(
                "{\"ok\":false,\"error_code\":400,\"description\":\"PHOTO_INVALID_DIMENSIONS\"}"));

        assertThatThrownBy(() -> client.getMe(TOKEN))
                .isInstanceOf(TelegramApiException.class)
                .hasMessageContaining("PHOTO_INVALID_DIMENSIONS");
    }

    @Test
    void httpErrorWithoutDescriptionFallsBackToHttpStatus() {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("Bad Gateway"));

        assertThatThrownBy(() -> client.getMe(TOKEN))
                .isInstanceOf(TelegramApiException.class)
                .hasMessageContaining("HTTP 502");
    }

    // ---- Spec 79: destino dinâmico (D5) e mídia CDN (D7) ----

    @Test
    void listChatsCollectsChannelsAndGroupsFromUpdates() throws Exception {
        server.enqueue(jsonResponse("{\"ok\":true,\"result\":["
                + "{\"update_id\":1,\"message\":{\"chat\":{\"id\":-100123,\"type\":\"channel\",\"title\":\"Ofertas\"}}},"
                + "{\"update_id\":2,\"message\":{\"chat\":{\"id\":-100123,\"type\":\"channel\",\"title\":\"Ofertas\"}}},"
                + "{\"update_id\":3,\"my_chat_member\":{\"chat\":{\"id\":-100456,\"type\":\"supergroup\",\"title\":\"Grupo VIP\"}}},"
                + "{\"update_id\":4,\"message\":{\"chat\":{\"id\":42,\"type\":\"private\",\"username\":\"fulano\"}}}"
                + "]}"));

        List<TelegramBotApi.ChatInfo> chats = client.listChats(TOKEN);

        // Dedup do canal + grupo; DM (private) é filtrada — não é destino.
        assertThat(chats).hasSize(2);
        assertThat(chats).extracting(TelegramBotApi.ChatInfo::id)
                .containsExactlyInAnyOrder("-100123", "-100456");
        assertThat(chats.get(0).type()).isEqualTo("channel");
    }

    @Test
    void listChatsReturnsEmptyWhenGetUpdatesFails() throws Exception {
        server.enqueue(jsonResponse("{\"ok\":false,\"error_code\":409,\"description\":\"terminated by other getUpdates\"}"));

        List<TelegramBotApi.ChatInfo> chats = client.listChats(TOKEN);

        // Webhook ativo (produção) → lista vazia (renderizador reporta via API).
        assertThat(chats).isEmpty();
    }

    @Test
    void sendMediaUploadsToCdnChannelAndReturnsFileId() throws Exception {
        properties.setTelegramCdnChannelId("-100999");

        server.enqueue(jsonResponse(
                "{\"ok\":true,\"result\":{\"message_id\":5,\"photo\":[{\"file_id\":\"AgAC-fileid\"}],\"chat\":{\"id\":-100999}}}"));

        String fileId = client.sendMedia(TOKEN, "image", new byte[]{1, 2, 3}, "banner.jpg");

        assertThat(fileId).isEqualTo("AgAC-fileid");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/bot123456%3AABC-secret/sendPhoto");
        assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("name=\"chat_id\"");
        assertThat(body).contains("-100999");
        assertThat(body).contains("filename=\"banner.jpg\"");
    }
}
