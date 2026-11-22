package com.foxcash.context;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Camada 1 — parse do YAML de configuração (Spec 68).
 *
 * <p>Carrega TODOS os {@code application*.yml} (base, dev, prod, railway) com o
 * MESMO loader do boot ({@link YamlPropertySourceLoader}): uma chave duplicada
 * no mesmo mapeamento (caso L16 — Spec 66) ou erro de sintaxe falha o
 * {@code mvn test} local, sem banco e sem subir a app. É a regressão direta do
 * bug que quebrou o boot em produção sem que a suíte unitária pegasse.
 */
class ApplicationConfigYamlTest {

    private static final String YAML_PREFIX = "application";

    @Test
    void allApplicationYamlFilesParseWithoutDuplicateKeysOrSyntaxErrors() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + YAML_PREFIX + "*.yml");

        // Os 4 arquivos de config reais DEVEM estar no classpath (sentinela de
        // que o scan encontrou os perfis; se um sumir, o teste falha).
        List<String> filenames = Arrays.stream(resources)
                .map(r -> r.getFilename() == null ? "" : r.getFilename())
                .toList();
        assertThat(filenames).contains(
                "application.yml", "application-dev.yml",
                "application-prod.yml", "application-railway.yml");

        for (Resource r : resources) {
            // DuplicateKeyException (L16) ou parse error (sintaxe) propaga AQUI.
            Properties props = TestYaml.loadProperties(r);
            assertThat(props)
                    .as("parse de %s", r.getFilename())
                    .isNotEmpty();
        }
    }

    @Test
    void securityAlertStorePropertyResolvesToMemoryByDefault() {
        // A chave que a Spec 66 inseriu como bloco duplicado (e que o fix
        // fundiu) resolve para o default 'memory' no yml base.
        assertThat(TestYaml.resolved("application.yml", "foxcash.security.alert.store"))
                .isEqualTo("memory");
    }

    @Test
    void duplicateKeyInSameMappingIsRejectedLikeTheL16Regression() {
        // Reprodução literal do bug da Spec 66 (chave 'alert:' DUAS vezes sob
        // foxcash.security): o mesmo loader do boot deve rejeitar — prova que a
        // sentinela funciona se a duplicata for reintroduzida.
        String broken = """
                foxcash:
                  security:
                    alert:
                      store: memory
                    alert:
                      enabled: true
                """;
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        ByteArrayResource resource =
                new ByteArrayResource(broken.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> loader.load("broken", resource))
                .as("chave duplicada no mesmo mapeamento deve quebrar o parse")
                .isInstanceOf(DuplicateKeyException.class);
    }
}
