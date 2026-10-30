package com.yizhaoqi.smartpai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.yizhaoqi.smartpai.service.reliability.DocumentStatusNotifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    /**
     * 订阅文档状态频道：任意实例上的状态变化都广播到所有实例，由持有用户 WebSocket 的实例推给前端
     */
    @Bean
    public RedisMessageListenerContainer documentStatusListenerContainer(RedisConnectionFactory connectionFactory,
                                                                         DocumentStatusNotifier documentStatusNotifier) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(documentStatusNotifier, new ChannelTopic(DocumentStatusNotifier.CHANNEL));
        return container;
    }
}
