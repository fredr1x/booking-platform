package dev.ydt.notification.repository;

import dev.ydt.notification.entity.BookingHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BookingHistoryRepository extends MongoRepository<BookingHistory, Long> {

    // идёт по индексу user_created: и фильтр, и сортировка без сортировки в памяти
    List<BookingHistory> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
