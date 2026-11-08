package dev.ydt.booking.contracts;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * Пропуск из виртуальной очереди: {@code payload.подпись}, HMAC-SHA256 общим секретом waiting-room и booking.
 * Проверка не ходит ни в Redis, ни в БД, поэтому на старте продаж не добавляет нагрузки.
 * Токен привязан к событию и пользователю: чужой или от другого события не подойдёт.
 */
public final class AdmissionToken {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private AdmissionToken() {
    }

    public static String issue(String secret, long eventId, String userId, Instant expiresAt) {
        // userId последним: только он может содержать разделитель
        String payload = ENCODER.encodeToString(
                (eventId + "|" + expiresAt.getEpochSecond() + "|" + userId).getBytes(StandardCharsets.UTF_8));
        return payload + "." + ENCODER.encodeToString(sign(secret, payload));
    }

    /** Токен приходит от клиента: любой мусор — это {@code false}, а не исключение. */
    public static boolean isValid(String secret, String token, long eventId, String userId, Instant now) {
        if (token == null) {
            return false;
        }
        try {
            int dot = token.indexOf('.');
            String payload = token.substring(0, dot);
            // сравнение за постоянное время: по времени ответа нельзя подбирать подпись побайтно
            if (!MessageDigest.isEqual(sign(secret, payload), DECODER.decode(token.substring(dot + 1)))) {
                return false;
            }
            String[] fields = new String(DECODER.decode(payload), StandardCharsets.UTF_8).split("\\|", 3);
            return Long.parseLong(fields[0]) == eventId
                    && Instant.ofEpochSecond(Long.parseLong(fields[1])).isAfter(now)
                    && fields[2].equals(userId);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return false;
        }
    }

    private static byte[] sign(String secret, String payload) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC is not available", e);
        }
    }
}
