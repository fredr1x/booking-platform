package dev.ydt.catalog.mapper;

import dev.ydt.catalog.dto.event.EventResponse;
import dev.ydt.catalog.entity.Event;
import dev.ydt.catalog.entity.EventDocument;
import dev.ydt.catalog.entity.Performer;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface EventMapper {

    @Mapping(target = "venue", source = "venue.name")
    EventResponse toResponse(Event event);

    List<EventResponse> toResponses(List<Event> events);

    @Mapping(target = "venue", source = "venue.name")
    EventDocument toDocument(Event event);

    List<EventDocument> toDocuments(List<Event> events);

    EventResponse toResponse(EventDocument document);

    List<EventResponse> toResponsesFromDocuments(List<EventDocument> documents);

    default String performerName(Performer performer) {
        return performer.getName();
    }
}
