package dev.ydt.booking.service.pricing;

/**
 * Заполненность зала события.
 *
 * @param takenSeats места в статусах HELD и BOOKED
 */
public record Occupancy(
        long totalSeats,
        long takenSeats
) {
}
