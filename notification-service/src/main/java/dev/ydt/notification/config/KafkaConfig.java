package dev.ydt.notification.config;

import dev.ydt.booking.contracts.Topics;
import dev.ydt.booking.contracts.web.RequestIdFilter;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;

/** Топик, который читает сервис. Один брокер, поэтому одна реплика. */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic bookingEventsTopic() {
        return TopicBuilder.name(Topics.BOOKING_EVENTS).partitions(Topics.PARTITIONS).replicas(1).build();
    }

    /** Request id из заголовка сообщения в MDC на время обработки: логи consumer'а ищутся по тому же id, что и HTTP-запрос. */
    @Bean
    public RecordInterceptor<Object, Object> requestIdInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                            Consumer<Object, Object> consumer) {
                var header = record.headers().lastHeader(RequestIdFilter.HEADER);
                if (header != null) {
                    MDC.put(RequestIdFilter.MDC_KEY, new String(header.value(), StandardCharsets.UTF_8));
                }
                return record;
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                MDC.remove(RequestIdFilter.MDC_KEY);
            }
        };
    }
}
