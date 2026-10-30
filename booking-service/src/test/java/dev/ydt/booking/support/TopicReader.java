package dev.ydt.booking.support;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Читает топик с начала своей группой, независимо от consumer'ов сервиса. */
public final class TopicReader implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    public TopicReader(String bootstrapServers, String topic) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
    }

    /** Читает, пока не наберётся {@code count} подходящих сообщений или не выйдет {@code timeout}. */
    public List<ConsumerRecord<String, String>> await(Predicate<ConsumerRecord<String, String>> filter, int count,
                                                      Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        while (matching(filter).size() < count && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        }
        return matching(filter);
    }

    private List<ConsumerRecord<String, String>> matching(Predicate<ConsumerRecord<String, String>> filter) {
        return received.stream().filter(filter).toList();
    }

    @Override
    public void close() {
        consumer.close();
    }
}
