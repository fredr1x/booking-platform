package dev.ydt.booking.config;

import dev.ydt.booking.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Топики, с которыми работает сервис. KafkaAdmin создаёт их на старте, если их ещё нет.
 * Один брокер, поэтому одна реплика; в проде 3 реплики и {@code min.insync.replicas=2}.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic bookingEventsTopic() {
        return TopicBuilder.name(Topics.BOOKING_EVENTS).partitions(Topics.PARTITIONS).replicas(1).build();
    }

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(Topics.PAYMENT_EVENTS).partitions(Topics.PARTITIONS).replicas(1).build();
    }
}
