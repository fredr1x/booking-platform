package dev.ydt.notification.controller;

import dev.ydt.notification.dto.booking.BookingHistoryResponse;
import dev.ydt.notification.service.BookingHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** История броней читается из MongoDB, БД booking-service этот запрос не трогает. */
@RestController
@RequestMapping("/api/v1/users/{userId}/bookings")
@RequiredArgsConstructor
public class UserBookingController {

    private static final int MAX_SIZE = 100;

    private final BookingHistoryService bookingHistoryService;

    @GetMapping
    public List<BookingHistoryResponse> list(@PathVariable String userId,
                                             @RequestHeader("X-User-Id") String currentUser,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        // shortcut: пользователь из заголовка, как в booking-service; с JWT (блок 10) возьмётся из токена
        if (!userId.equals(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only your own bookings");
        }
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page >= 0, size 1.." + MAX_SIZE);
        }
        return bookingHistoryService.findByUser(userId, page, size);
    }
}
