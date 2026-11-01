package dev.ydt.catalog.config;

import dev.ydt.catalog.dto.event.EventResponse;
import dev.ydt.catalog.service.EventService;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration EVENT_TTL = Duration.ofMinutes(10);
    private static final long JITTER_SECONDS = 60;

    /**
     * Карточка события в Redis на 10 минут плюс случайные 0–60 секунд. Без разброса ключи, прогретые
     * одной волной (старт продаж), истекли бы одновременно, и все запросы разом пошли бы в БД.
     * Тип значения задан явно: обобщённый JSON без информации о типе вернул бы LinkedHashMap вместо record.
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer eventCache() {
        return builder -> builder.withCacheConfiguration(EventService.EVENT_CACHE, RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl((key, value) -> EVENT_TTL.plusSeconds(ThreadLocalRandom.current().nextLong(JITTER_SECONDS + 1)))
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(EventResponse.class))));
    }
}
