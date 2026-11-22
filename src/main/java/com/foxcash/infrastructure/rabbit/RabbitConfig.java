package com.foxcash.infrastructure.rabbit;

import com.foxcash.infrastructure.config.RabbitProperties;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * Infra RabbitMQ do outbox (Spec 134) — só ativa com
 * {@code foxcash.rabbit.enabled=true} (opt-in; default desligado — o outbox
 * segue o caminho atual: tabela + worker em modo dreno).
 *
 * <p>O {@code RabbitAutoConfiguration} do Boot é EXCLUÍDO no application.yml
 * (Spec 134): o auto-config cria um {@link RabbitAdmin} que CONECTA no broker
 * durante o boot — inaceitável com a feature desligada. Aqui a configuração é
 * completa: {@link ConnectionFactory} (Caching, lazy até o primeiro uso),
 * {@link RabbitAdmin} (declara exchange/filas no boot — só existe quando
 * habilitado), {@link RabbitTemplate} + converter JSON.
 */
@Configuration
@EnableRabbit
@ConditionalOnProperty(name = "foxcash.rabbit.enabled", havingValue = "true")
public class RabbitConfig {

    @Value("${spring.rabbitmq.host:localhost}")
    private String host;

    @Value("${spring.rabbitmq.port:5672}")
    private int port;

    @Value("${spring.rabbitmq.username:guest}")
    private String username;

    @Value("${spring.rabbitmq.password:guest}")
    private String password;

    @Bean
    public ConnectionFactory rabbitConnectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        factory.setUsername(username);
        factory.setPassword(password);
        return factory;
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter rabbitMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(rabbitMessageConverter);
        return template;
    }

    /**
     * Container factory do {@code @RabbitListener} (Spec 134): o
     * {@code RabbitAutoConfiguration} do Boot é EXCLUÍDO — sem este bean o
     * {@code @RabbitListener} do {@code OutboxConsumer} nem registra listener
     * (achado T5.1 2026-08-17: app não bootava com {@code enabled=true}).
     *
     * <p><b>Retry/backoff + DLQ:</b> o listener é invocado até {@code max-attempts}
     * vezes (backoff exponencial {@code retry-*}); ao esgotar, o
     * {@link RejectAndDontRequeueRecoverer} rejeita SEM requeue → a mensagem cai
     * na DLQ (dead-letter do {@code outboxQueue} — o operador enxerga e dá
     * replay via management API, Spec 67).
     *
     * <p>Consumo paralelo (4 consumidores, prefetch 20) para o p95 < 5s sob
     * carga (Spec 134/62).
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter,
            RabbitProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setConcurrentConsumers(4);
        factory.setPrefetchCount(20);
        RetryOperationsInterceptor retry = RetryInterceptorBuilder.stateless()
                .maxAttempts(properties.getMaxAttempts())
                .backOffOptions(properties.getRetryInitialIntervalMs(),
                        properties.getRetryMultiplier(),
                        properties.getRetryMaxIntervalMs())
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
        factory.setAdviceChain(retry);
        return factory;
    }

    @Bean
    public DirectExchange outboxExchange(RabbitProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    @Bean
    public Queue outboxQueue(RabbitProperties properties) {
        // Dead-letter: mensagens rejeitadas sem requeue (falharam N vezes no
        // consumidor) vão para a DLQ — o operador enxerga e pode dar replay.
        return QueueBuilder.durable(properties.getQueue())
                .deadLetterExchange("")
                .deadLetterRoutingKey(properties.getDeadLetterQueue())
                .build();
    }

    @Bean
    public Queue outboxDeadLetterQueue(RabbitProperties properties) {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    @Bean
    public Binding outboxBinding(Queue outboxQueue, DirectExchange outboxExchange,
                                 RabbitProperties properties) {
        return BindingBuilder.bind(outboxQueue).to(outboxExchange)
                .with(properties.getRoutingKey());
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        // Envelope JSON — o payload já é o JSON do outbox (Spec 35). O default do
        // Jackson2JsonMessageConverter só confia em java.util/java.lang: sem
        // trusted packages o envelope do OutboxRelay (com.foxcash.*) é rejeitado
        // no consume ("type id not trusted") — achado T5.1 2026-08-17.
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.foxcash", "java.util", "java.lang", "java.time");
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
