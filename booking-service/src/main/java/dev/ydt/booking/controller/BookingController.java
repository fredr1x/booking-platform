package dev.ydt.booking.controller;

import dev.ydt.booking.dto.booking.BookingResponse;
import dev.ydt.booking.dto.booking.CreateBookingRequest;
import dev.ydt.booking.service.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final BookingService bookingService;

    @PostMapping
    public ResponseEntity<BookingResponse> create(@CurrentUser String userId,
                                                  @RequestHeader(name = IDEMPOTENCY_HEADER, required = false)
                                                  @Size(max = 255) String idempotencyKey,
                                                  @Valid @RequestBody CreateBookingRequest request) {
        BookingResponse booking = bookingService.create(userId, idempotencyKey, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(booking.id()).toUri();
        return ResponseEntity.created(location).body(booking);
    }

    @GetMapping("/{id}")
    public BookingResponse get(@CurrentUser String userId, @PathVariable Long id) {
        return bookingService.get(id, userId);
    }

    @PostMapping("/{id}/pay")
    public BookingResponse pay(@CurrentUser String userId, @PathVariable Long id) {
        return bookingService.pay(id, userId);
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@CurrentUser String userId, @PathVariable Long id) {
        return bookingService.cancel(id, userId);
    }
}
