package dev.ydt.booking.mapper;

import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.dto.booking.BookingSeatResponse;
import dev.ydt.booking.entity.Booking;
import dev.ydt.booking.entity.BookingSeat;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface BookingMapper {

    @Mapping(target = "eventId", source = "event.id")
    BookingResponse toResponse(Booking booking);

    @Mapping(target = "seatId", source = "seat.id")
    @Mapping(target = "section", source = "seat.section")
    @Mapping(target = "row", source = "seat.rowLabel")
    @Mapping(target = "number", source = "seat.seatNumber")
    BookingSeatResponse toSeatResponse(BookingSeat bookingSeat);
}
