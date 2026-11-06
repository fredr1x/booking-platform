package dev.ydt.catalog.repository;

import dev.ydt.catalog.entity.Event;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Лента событий постранично через keyset: следующая страница начинается строго после последней
 * строки предыдущей, (starts_at, id) > (?, ?). Индекс idx_events_on_sale* отдаёт строки уже в нужном
 * порядке, поэтому глубина страницы на скорость не влияет, в отличие от OFFSET.
 * <p>
 * Статус записан литералом, а не параметром: частичный индекс WHERE status = 'ON_SALE' планировщик
 * может применить, только если видит это условие в тексте запроса.
 * <p>
 * JOIN FETCH venue приносит площадку тем же запросом. Артистов так не грузим: fetch-join коллекции
 * вместе с LIMIT Hibernate выполняет в памяти. Они догружаются одной пачкой (default_batch_fetch_size).
 */
public interface EventRepository extends JpaRepository<Event, Long> {

    @Query("""
            select e from Event e join fetch e.venue
            where e.status = dev.ydt.catalog.entity.enums.EventStatus.ON_SALE
              and (e.startsAt, e.id) > (:startsAt, :id)
            order by e.startsAt, e.id
            """)
    List<Event> findOnSaleAfter(Instant startsAt, long id, Limit limit);

    @Query("""
            select e from Event e join fetch e.venue
            where e.status = dev.ydt.catalog.entity.enums.EventStatus.ON_SALE
              and e.city = :city
              and (e.startsAt, e.id) > (:startsAt, :id)
            order by e.startsAt, e.id
            """)
    List<Event> findOnSaleInCityAfter(String city, Instant startsAt, long id, Limit limit);

    // переиндексация пачками: площадка JOIN'ом, артисты догружаются пачкой (default_batch_fetch_size)
    @EntityGraph(attributePaths = "venue")
    List<Event> findByIdGreaterThanOrderById(long id, Limit limit);

    // карточка события: площадка и артисты одним запросом
    @EntityGraph(attributePaths = {"venue", "performers"})
    Optional<Event> findWithDetailsById(Long id);
}
