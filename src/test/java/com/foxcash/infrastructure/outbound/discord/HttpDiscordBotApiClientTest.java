package com.foxcash.infrastructure.outbound.discord;

import com.foxcash.domain.port.out.DiscordBotApi;
import com.foxcash.infrastructure.config.BotProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link HttpDiscordBotApiClient} (Spec 74/T3.4 + Spec 78/T2.1) —
 * mock de contrato da API REST do Discord v10 contra um MockWebServer: getMe
 * (identidade com Bearer Bot), putCommands e updateProfile (PATCH /users/@me
 * com avatar data URI base64 + descrição).
 */
class HttpDiscordBotApiClientTest {

    private static final String TOKEN = "d-token-secreto";
    private static final String APP_ID = "123456789";

    private MockWebServer server;
    private HttpDiscordBotApiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        BotProperties properties = new BotProperties();
        properties.setDiscordApiBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        client = new HttpDiscordBotApiClient(properties);
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
                "{\"id\":\"123\",\"username\":\"meubot\",\"global_name\":\"Meu Bot\"}"));

        DiscordBotApi.DiscordUserInfo me = client.getMe(TOKEN);

        assertThat(me.id()).isEqualTo(123L);
        assertThat(me.username()).isEqualTo("meubot");
        assertThat(me.displayName()).isEqualTo("Meu Bot");

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("GET");
        assertThat(request.getPath()).isEqualTo("/users/@me");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bot " + TOKEN);
    }

    @Test
    void putCommandsRegistersSlashCommands() throws Exception {
        server.enqueue(jsonResponse("[]"));

        client.putCommands(APP_ID, TOKEN,
                List.of(new DiscordBotApi.SlashCommand("comprar", "Escolha um plano e compre")));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("PUT");
        assertThat(request.getPath()).isEqualTo("/applications/" + APP_ID + "/commands");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bot " + TOKEN);
        assertThat(request.getBody().readUtf8()).contains("\"name\":\"comprar\"");
    }

    @Test
    void updateProfileSendsDescriptionAndAvatarAsDataUri() throws Exception {
        server.enqueue(jsonResponse("{}"));

        byte[] avatar = new byte[]{1, 2, 3, 4};
        client.updateProfile(TOKEN, "Bot de vendas", avatar);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getMethod()).isEqualTo("PATCH");
        assertThat(request.getPath()).isEqualTo("/users/@me");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bot " + TOKEN);
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"bio\":\"Bot de vendas\"");
        assertThat(body).contains("\"avatar\":\"data:image/png;base64,AQIDBA==\"");
    }

    @Test
    void updateProfileSkipsCallWhenNoFields() throws Exception {
        // Nenhum campo → sem chamada HTTP (o adapter retorna cedo).
        client.updateProfile(TOKEN, null, null);
        assertThat(server.getRequestCount()).isZero();
    }
}
