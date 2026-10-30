package dev.ydt.notification.config;

import dev.ydt.booking.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Топик, который читает сервис. Один брокер, поэтому одна реплика. */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic bookingEventsTopic() {
        return TopicBuilder.name(Topics.BOOKING_EVENTS).partitions(Topics.PARTITIONS).replicas(1).build();
    }
}
