package com.foxcash.infrastructure.inbound.rest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes do {@link ConsoleSpaController} (Spec 71, fatia 1): o fallback das
 * rotas client-side do Master Console encaminha para o
 * {@code console/index.html} (mesmo padrão do {@code PortalSpaController}), e o
 * bean é condicional ao flag {@code foxcash.console.serve-frontend=true}.
 *
 * <p>A ausência do bean sem o flag (nenhuma rota extra exposta) e o 401 em
 * rota desconhecida são cobertos pelo boot real/E2E (o Spring aplica o
 * {@code @ConditionalOnProperty} no arranque do contexto).
 */
class ConsoleSpaControllerTest {

    @Test
    void forwardToIndexReturnsConsoleIndexHtml() {
        ConsoleSpaController controller = new ConsoleSpaController();

        assertThat(controller.forwardToIndex()).isEqualTo("forward:/console/index.html");
    }

    @Test
    void beanIsConditionalOnServeFrontendFlag() throws Exception {
        ConditionalOnProperty annotation = ConsoleSpaController.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).containsExactly("foxcash.console.serve-frontend");
        assertThat(annotation.havingValue()).isEqualTo("true");
    }

    @Test
    void mappingCoversClientSideRoutesAndIsLiteral() throws Exception {
        Method method = ConsoleSpaController.class.getMethod("forwardToIndex");
        org.springframework.web.bind.annotation.RequestMapping mapping =
                method.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.path()).contains(
                "/console", "/console/", "/console/login", "/console/dashboard", "/console/transactions",
                "/console/merchants", "/console/merchants/*", "/console/withdrawals",
                "/console/financial", "/console/receivables", "/console/audit",
                "/console/activity", "/console/reconciliation", "/console/lookup",
                "/console/bots",
                // Spec 127: trace de transação (rota client-side do react-router).
                "/console/trace",
                "/console/security", "/console/profiles");
        // Sem /console/** amplo — os assets (/console/assets/**) ficam para o
        // handler de recursos estáticos (a ordem do Spring MVC colocaria o
        // controller à frente e quebraria a SPA).
        assertThat(mapping.path()).doesNotContain("/console/**");
    }
}
