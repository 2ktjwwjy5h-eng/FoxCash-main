package com.foxcash.infrastructure.config;

import com.foxcash.application.service.ChatEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * Fan-out multi-instância do chat do CRM (Spec 83 D8) — assina
 * {@code foxcash:crm:events:*} no Redis pub/sub e encaminha cada evento aos SSEs
 * locais da instância ({@link ChatEventPublisher#onRedisEvent}). Condicional
 * ({@code foxcash.crm.redis-enabled=true}): dev/E2E/CI sem Redis usam apenas a
 * entrega local in-memory (fail-open ADR-010).
 */
@Configuration
@ConditionalOnProperty(prefix = "foxcash.crm", name = "redis-enabled", havingValue = "true")
public class CrmRedisConfig {

    @Bean
    public RedisMessageListenerContainer crmRedisListenerContainer(RedisConnectionFactory connectionFactory,
                                                                   CrmProperties properties,
                                                                   ChatEventPublisher publisher) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            publisher.onRedisEvent(channel, body);
        }, new PatternTopic(properties.getChatEventChannelPrefix() + "*"));
        return container;
    }
}
