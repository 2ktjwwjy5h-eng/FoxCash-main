package com.foxcash.context;

import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.PropertyPlaceholderHelper;

import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/**
 * Helper dos testes de contexto (Spec 68 — camadas 1 e 3).
 *
 * <p>Carrega os {@code application*.yml} com o <b>MESMO loader do boot</b>:
 * {@link YamlPropertySourceLoader} (é o loader que o Spring Boot usa no
 * arranque — `ConfigDataEnvironmentPostProcessor`). Uma chave duplicada no
 * mesmo mapeamento lança {@code DuplicateKeyException} (L16) e TODOS os
 * valores escalares são preservados (String/int/boolean — o
 * {@code YamlPropertiesFactoryBean} NÃO serve: ele descarta scalars não-String,
 * achado da auditoria da Spec 68).
 *
 * <p>{@link #resolved} resolve placeholders {@code ${ENV:default}} com os
 * defaults do próprio arquivo (via {@link PropertyPlaceholderHelper} com
 * separador {@code ':'}) — o loader NÃO resolve placeholders (isso acontece
 * depois, no property source), então o helper faz o passo que falta para
 * leituras de valor (camada 3) e asserts de chave (camada 1).
 */
final class TestYaml {

    private TestYaml() {
    }

    /** Parse de um {@code application*.yml} do classpath (main resources). */
    static Properties loadProperties(String resourceName) {
        return loadProperties(new ClassPathResource(resourceName));
    }

    /** Parse de um Resource YAML qualquer (mesmo loader do boot). */
    static Properties loadProperties(Resource resource) {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources;
        try {
            // DuplicateKeyException / parse error propaga AQUI (mesmo do boot).
            sources = loader.load(resource.getFilename() == null ? "yaml" : resource.getFilename(), resource);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Falha ao carregar " + resource, e);
        }
        Properties props = new Properties();
        for (PropertySource<?> source : sources) {
            if (!(source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                Object value = source.getProperty(name);
                props.setProperty(name, value == null ? null : String.valueOf(value));
            }
        }
        if (props.isEmpty()) {
            throw new IllegalStateException("YAML vazio ou não carregado: " + resource);
        }
        return props;
    }

    /** Valor de uma chave com placeholders {@code ${ENV:default}} resolvidos. */
    static String resolved(String resourceName, String key) {
        return resolved(resourceName, key, System::getenv);
    }

    /** Variante com lookup de env injetável (testabilidade do caminho env). */
    static String resolved(String resourceName, String key, Function<String, String> envLookup) {
        Properties props = loadProperties(resourceName);
        String raw = props.getProperty(key);
        if (raw == null) {
            throw new AssertionError("Chave não encontrada em " + resourceName + ": " + key);
        }
        return resolvePlaceholders(raw, envLookup);
    }

    /** Properties com TODOS os valores resolvidos (para o {@code Binder}). */
    static Properties resolvedProperties(String resourceName) {
        return resolvedProperties(resourceName, System::getenv);
    }

    /** Variante com lookup de env injetável (testabilidade do caminho env). */
    static Properties resolvedProperties(String resourceName, Function<String, String> envLookup) {
        Properties props = loadProperties(resourceName);
        Properties out = new Properties();
        for (String name : props.stringPropertyNames()) {
            out.setProperty(name, resolvePlaceholders(props.getProperty(name), envLookup));
        }
        return out;
    }

    private static final PropertyPlaceholderHelper HELPER =
            new PropertyPlaceholderHelper("${", "}", ":", true);

    private static String resolvePlaceholders(String value, Function<String, String> envLookup) {
        return HELPER.replacePlaceholders(value, envLookup::apply);
    }
}
