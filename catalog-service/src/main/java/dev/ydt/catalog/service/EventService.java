package dev.ydt.catalog.service;

import dev.ydt.catalog.dto.event.EventCursor;
import dev.ydt.catalog.dto.event.EventPage;
import dev.ydt.catalog.dto.event.EventResponse;
import dev.ydt.catalog.entity.Event;
import dev.ydt.catalog.exception.EventNotFoundException;
import dev.ydt.catalog.mapper.EventMapper;
import dev.ydt.catalog.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EventService {

    public static final String EVENT_CACHE = "events";

    private final EventRepository eventRepository;
    private final EventMapper eventMapper;

    /** Ближайшие события в продаже. Без курсора лента начинается с текущего момента. */
    public EventPage findOnSale(String city, String after, int size) {
        EventCursor cursor = after == null ? new EventCursor(Instant.now(), 0) : EventCursor.decode(after);
        // на одну строку больше: так без отдельного count(*) понятно, есть ли следующая страница
        var limit = Limit.of(size + 1);
        List<Event> events = city == null
                ? eventRepository.findOnSaleAfter(cursor.startsAt(), cursor.id(), limit)
                : eventRepository.findOnSaleInCityAfter(city, cursor.startsAt(), cursor.id(), limit);

        boolean hasNext = events.size() > size;
        List<Event> page = hasNext ? events.subList(0, size) : events;
        String nextCursor = hasNext ? new EventCursor(page.getLast().getStartsAt(), page.getLast().getId()).encode() : null;
        return new EventPage(eventMapper.toResponses(page), nextCursor);
    }

    /**
     * Cache-aside: сначала Redis, при промахе БД и запись в Redis. {@code sync = true}: на промахе в БД идёт
     * один поток инстанса, остальные ждут его (защита от cache stampede). Отсутствующее событие не кэшируется:
     * исключение пролетает мимо кэша.
     */
    // shortcut: событие не меняется после публикации, поэтому кэш живёт до TTL; появится редактирование — @CacheEvict
    @Cacheable(cacheNames = EVENT_CACHE, key = "#id", sync = true)
    public EventResponse findById(Long id) {
        return eventMapper.toResponse(eventRepository.findWithDetailsById(id)
                .orElseThrow(() -> new EventNotFoundException(id)));
    }
}
