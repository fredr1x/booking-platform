package dev.ydt.booking.service.pricing;

import dev.ydt.booking.entity.enums.SeatStatus;
import dev.ydt.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Подсчёт мест по статусам — это проход по 20 000 строк. Без кэша он шёл на каждую бронь, а на старте
 * продаж это тысячи одинаковых запросов в секунду. Цене по спросу секундная неточность не мешает.
 * <p>
 * {@code sync = true}: при промахе считает один поток инстанса, остальные ждут его результат.
 */
@Service
@RequiredArgsConstructor
public class OccupancyService {

    public static final String CACHE = "occupancy";

    private final SeatRepository seatRepository;

    @Cacheable(cacheNames = CACHE, key = "#eventId", sync = true)
    public Occupancy occupancy(Long eventId) {
        long total = 0;
        long available = 0;
        for (SeatRepository.StatusCount count : seatRepository.countByStatus(eventId)) {
            total += count.getCount();
            if (count.getStatus() == SeatStatus.AVAILABLE) {
                available = count.getCount();
            }
        }
        return new Occupancy(total, total - available);
    }
}
