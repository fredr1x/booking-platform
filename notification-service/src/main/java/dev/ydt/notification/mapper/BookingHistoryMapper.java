package dev.ydt.notification.mapper;

import dev.ydt.notification.dto.booking.BookingHistoryResponse;
import dev.ydt.notification.entity.BookingHistory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper
public interface BookingHistoryMapper {

    @Mapping(target = "updatedAt", source = "statusAt")
    BookingHistoryResponse toResponse(BookingHistory history);

    List<BookingHistoryResponse> toResponses(List<BookingHistory> histories);
}
