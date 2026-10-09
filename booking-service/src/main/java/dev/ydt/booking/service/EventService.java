package dev.ydt.booking.service;

import dev.ydt.booking.dto.event.AvailabilityResponse;
import dev.ydt.booking.dto.event.EventResponse;
import dev.ydt.booking.dto.seat.SeatResponse;
import dev.ydt.booking.entity.Event;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.mapper.EventMapper;
import dev.ydt.booking.mapper.SeatMapper;
import dev.ydt.booking.repository.EventRepository;
import dev.ydt.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final EventMapper eventMapper;
    private final SeatMapper seatMapper;

    public List<EventResponse> findAll() {
        return eventMapper.toResponses(eventRepository.findAll(Sort.by("startsAt")));
    }

    public EventResponse findById(Long id) {
        return eventMapper.toResponse(getEvent(id));
    }

    public AvailabilityResponse getAvailability(Long eventId) {
        getEvent(eventId);
        Map<SeatStatus, Long> counts = new EnumMap<>(SeatStatus.class);
        for (SeatStatus status : SeatStatus.values()) {
            counts.put(status, 0L);
        }
        seatRepository.countByStatus(eventId).forEach(c -> counts.put(c.getStatus(), c.getCount()));
        return new AvailabilityResponse(eventId, counts);
    }

    public List<SeatResponse> findSeats(Long eventId, SeatStatus status, int limit) {
        getEvent(eventId);
        return seatMapper.toResponses(
                seatRepository.findByEventIdAndStatusOrderByIdAsc(eventId, status, Limit.of(limit)));
    }

    // Своё исключение и глобальный обработчик ошибок появятся в блоке 4.
    private Event getEvent(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event " + id + " not found"));
    }
}
