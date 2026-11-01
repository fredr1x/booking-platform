package dev.ydt.booking.config;

import dev.ydt.booking.service.pricing.Occupancy;
import dev.ydt.booking.service.pricing.OccupancyService;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    // Сериализатор с конкретным типом: обобщённый JSON без информации о типе вернул бы LinkedHashMap вместо record
    @Bean
    public RedisCacheManagerBuilderCustomizer occupancyCache() {
        return builder -> builder.withCacheConfiguration(OccupancyService.CACHE, RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(1))
                .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(Occupancy.class))));
    }
}
