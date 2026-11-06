package dev.ydt.catalog.entity;

import dev.ydt.catalog.entity.enums.EventStatus;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.time.Instant;
import java.util.List;

/**
 * Событие в индексе Elasticsearch: денормализованная копия строки events с именами площадки и артистов,
 * чтобы поиск шёл по одному документу без JOIN. Источник правды — PostgreSQL.
 * <p>
 * text-поля разбиваются на слова и ищутся с опечатками, keyword-поля сравниваются целиком для фильтров.
 * Индекс создаётся кодом ({@code createIndex = false}): иначе каталог не стартовал бы без Elasticsearch.
 */
@Document(indexName = "events", createIndex = false)
// один узел: реплику некуда поставить, с replicas = 1 индекс навсегда был бы yellow
@Setting(shards = 1, replicas = 0)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventDocument {

    @Id
    private Long id;

    @Field(type = FieldType.Text)
    private String title;

    @Field(type = FieldType.Keyword)
    private String city;

    @Field(type = FieldType.Text)
    private String venue;

    @Field(type = FieldType.Date)
    private Instant startsAt;

    @Field(type = FieldType.Date)
    private Instant salesOpenAt;

    @Field(type = FieldType.Keyword)
    private EventStatus status;

    @Field(type = FieldType.Text)
    private List<String> performers;

    public EventDocument(Long id, String title, String city, String venue, Instant startsAt, Instant salesOpenAt,
                         EventStatus status, List<String> performers) {
        this.id = id;
        this.title = title;
        this.city = city;
        this.venue = venue;
        this.startsAt = startsAt;
        this.salesOpenAt = salesOpenAt;
        this.status = status;
        this.performers = performers;
    }
}
