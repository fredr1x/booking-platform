package dev.ydt.catalog.mapper;

import dev.ydt.catalog.dto.event.EventResponse;
import dev.ydt.catalog.entity.Event;
import dev.ydt.catalog.entity.Performer;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface EventMapper {

    @Mapping(target = "venue", source = "venue.name")
    EventResponse toResponse(Event event);

    List<EventResponse> toResponses(List<Event> events);

    default String performerName(Performer performer) {
        return performer.getName();
    }
}
