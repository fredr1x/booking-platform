package dev.ydt.waitingroom.dto.queue;

import dev.ydt.waitingroom.entity.enums.QueueState;

import java.time.Instant;

/**
 * @param position             место в очереди, с 1; null после пропуска
 * @param estimatedWaitSeconds примерное ожидание при текущей скорости пропуска; null после пропуска
 * @param retryAfterSeconds    когда опросить статус снова; null после пропуска
 * @param admissionToken       передать в заголовке X-Admission-Token при бронировании; null, пока ждёт
 * @param tokenExpiresAt       до какого момента действует токен; null, пока ждёт
 */
public record QueueStatusResponse(
        QueueState state,
        Long position,
        Long estimatedWaitSeconds,
        Long retryAfterSeconds,
        String admissionToken,
        Instant tokenExpiresAt
) {
}
