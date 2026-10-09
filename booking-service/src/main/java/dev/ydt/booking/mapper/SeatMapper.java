package dev.ydt.booking.mapper;

import dev.ydt.booking.dto.seat.SeatResponse;
import dev.ydt.booking.entity.Seat;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface SeatMapper {

    @Mapping(target = "row", source = "rowLabel")
    @Mapping(target = "number", source = "seatNumber")
    SeatResponse toResponse(Seat seat);

    List<SeatResponse> toResponses(List<Seat> seats);
}
