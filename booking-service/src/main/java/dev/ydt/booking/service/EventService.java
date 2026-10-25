package dev.ydt.booking.service;

import dev.ydt.booking.dto.event.AvailabilityResponse;
import dev.ydt.booking.dto.seat.SeatResponse;
import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.exception.EventNotFoundException;
import dev.ydt.booking.mapper.SeatMapper;
import dev.ydt.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Инвентарь мест события. Само событие (название, дата, площадка) в catalog-service. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EventService {

    private final SeatRepository seatRepository;
    private final SeatMapper seatMapper;

    public AvailabilityResponse getAvailability(Long eventId) {
        List<SeatRepository.StatusCount> rows = seatRepository.countByStatus(eventId);
        // мест нет, значит booking-service это событие не продаёт
        if (rows.isEmpty()) {
            throw new EventNotFoundException(eventId);
        }
        Map<SeatStatus, Long> counts = new EnumMap<>(SeatStatus.class);
        for (SeatStatus status : SeatStatus.values()) {
            counts.put(status, 0L);
        }
        rows.forEach(c -> counts.put(c.getStatus(), c.getCount()));
        return new AvailabilityResponse(eventId, counts);
    }

    public List<SeatResponse> findSeats(Long eventId, SeatStatus status, int limit) {
        return seatMapper.toResponses(
                seatRepository.findByEventIdAndStatusOrderByIdAsc(eventId, status, Limit.of(limit)));
    }
}
