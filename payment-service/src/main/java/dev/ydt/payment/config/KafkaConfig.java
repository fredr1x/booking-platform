package dev.ydt.payment.config;

import dev.ydt.booking.contracts.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Топики сервиса. Один брокер, поэтому одна реплика. */
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
