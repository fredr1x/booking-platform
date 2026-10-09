package dev.ydt.booking.mapper;

import dev.ydt.booking.dto.event.EventResponse;
import dev.ydt.booking.entity.Event;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper
public interface EventMapper {

    EventResponse toResponse(Event event);

    List<EventResponse> toResponses(List<Event> events);
}
