package com.foxcash.context;

import com.foxcash.infrastructure.config.BotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertiesPropertySource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Camada 3 — binding de properties (Spec 68/74): vincula o {@link BotProperties}
 * via {@link Binder} (o MESMO mecanismo do {@code @ConfigurationProperties} do
 * boot) contra os valores REAIS do {@code application.yml} — se o yml e o
 * código divergirem, o teste falha (sentinela de alinhamento código ↔
 * configuração). O parse do yml completo (TestYaml.loadProperties) também
 * garante que o bloco {@code foxcash.bots} NÃO colidiu com chaves existentes
 * (L16).
 */
class BotPropertiesBindingTest {

    private static final String YAML = "application.yml";

    private static BotProperties bind(Properties props) {
        Binder binder = new Binder(ConfigurationPropertySources.from(
                new PropertiesPropertySource(YAML, props)));
        return binder.bind("foxcash.bots", Bindable.of(BotProperties.class))
                .orElseThrow(() -> new AssertionError("binding de foxcash.bots falhou"));
    }

    @Test
    void bindsRealYamlDefaults() {
        BotProperties props = bind(TestYaml.resolvedProperties(YAML));

        assertThat(props.getWebhookPublicBaseUrl()).isEmpty();
        assertThat(props.isReconcileEnabled()).isTrue();
        assertThat(props.getReconcileCron()).isEqualTo("0 */5 * * * *");
        assertThat(props.isDiscordGatewayEnabled()).isFalse();
        assertThat(props.getTelegramApiBaseUrl()).isEqualTo("https://api.telegram.org");
        assertThat(props.getRegisteringTimeoutMinutes()).isEqualTo(30);
        assertThat(props.getHealthTimeoutMinutes()).isEqualTo(60);
    }

    @Test
    void overrideIsRespected() {
        Properties props = TestYaml.resolvedProperties(YAML);
        props.setProperty("foxcash.bots.webhook-public-base-url", "https://api.foxcash.example.com");
        props.setProperty("foxcash.bots.reconcile-enabled", "false");
        props.setProperty("foxcash.bots.discord-gateway-enabled", "true");

        BotProperties bound = bind(props);

        assertThat(bound.getWebhookPublicBaseUrl()).isEqualTo("https://api.foxcash.example.com");
        assertThat(bound.isReconcileEnabled()).isFalse();
        assertThat(bound.isDiscordGatewayEnabled()).isTrue();
    }

    @Test
    void botsBlockDoesNotCollideWithExistingKeys() {
        // L16 (auditoria Spec 66): chave duplicada no mesmo nível quebra o boot.
        // O parse do yml completo (TestYaml.loadProperties) lança DuplicateKeyException
        // se houver duplicata — garante que o bloco bots não colidiu.
        Properties props = TestYaml.resolvedProperties(YAML);
        assertThat(props.getProperty("foxcash.bots.reconcile-enabled")).isEqualTo("true");
        assertThat(props.getProperty("foxcash.auto-withdraw.enabled")).isNotNull();
        assertThat(props.getProperty("foxcash.psp.provider")).isNotNull();
    }
}
