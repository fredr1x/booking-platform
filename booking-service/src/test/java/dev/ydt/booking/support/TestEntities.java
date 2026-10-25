package dev.ydt.booking.support;

import dev.ydt.booking.dto.event.CatalogEvent;
import dev.ydt.booking.entity.Seat;
import dev.ydt.booking.entity.enums.EventStatus;
import dev.ydt.booking.entity.enums.SeatStatus;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

// У сущностей нет публичных конструкторов и сеттеров, поэтому для unit-тестов собираем их через reflection.
public final class TestEntities {

    private TestEntities() {
    }

    public static CatalogEvent event(Long id, EventStatus status, Instant salesOpenAt) {
        return new CatalogEvent(id, status, salesOpenAt);
    }

    public static Seat seat(Long id, CatalogEvent event, String price) {
        return seat(id, event, price, SeatStatus.AVAILABLE);
    }

    public static Seat seat(Long id, CatalogEvent event, String price, SeatStatus status) {
        Seat seat = BeanUtils.instantiateClass(Seat.class);
        ReflectionTestUtils.setField(seat, "id", id);
        ReflectionTestUtils.setField(seat, "eventId", event.id());
        ReflectionTestUtils.setField(seat, "section", "A");
        ReflectionTestUtils.setField(seat, "rowLabel", "1");
        ReflectionTestUtils.setField(seat, "seatNumber", id.intValue());
        ReflectionTestUtils.setField(seat, "price", new BigDecimal(price));
        ReflectionTestUtils.setField(seat, "status", status);
        return seat;
    }
}
