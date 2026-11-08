package dev.ydt.booking.contracts;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionTokenTest {

    private static final String SECRET = "test-secret-test-secret-test-secret";
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(600);

    private final String token = AdmissionToken.issue(SECRET, 2, "alice", EXPIRES);

    @Test
    void acceptsOwnTokenBeforeExpiry() {
        assertThat(AdmissionToken.isValid(SECRET, token, 2, "alice", NOW)).isTrue();
    }

    @Test
    void userIdMayContainSeparator() {
        String piped = AdmissionToken.issue(SECRET, 2, "a|b|c", EXPIRES);

        assertThat(AdmissionToken.isValid(SECRET, piped, 2, "a|b|c", NOW)).isTrue();
    }

    @Test
    void rejectsExpiredOtherUserOtherEventAndOtherSecret() {
        assertThat(AdmissionToken.isValid(SECRET, token, 2, "alice", EXPIRES)).isFalse();
        assertThat(AdmissionToken.isValid(SECRET, token, 2, "bob", NOW)).isFalse();
        assertThat(AdmissionToken.isValid(SECRET, token, 3, "alice", NOW)).isFalse();
        assertThat(AdmissionToken.isValid("another-secret-another-secret-1234", token, 2, "alice", NOW)).isFalse();
    }

    @Test
    void rejectsTamperedPayloadAndGarbage() {
        String swappedPayload = AdmissionToken.issue(SECRET, 2, "mallory", EXPIRES).split("\\.")[0]
                + "." + token.split("\\.")[1];

        assertThat(AdmissionToken.isValid(SECRET, swappedPayload, 2, "mallory", NOW)).isFalse();
        assertThat(AdmissionToken.isValid(SECRET, null, 2, "alice", NOW)).isFalse();
        assertThat(AdmissionToken.isValid(SECRET, "", 2, "alice", NOW)).isFalse();
        assertThat(AdmissionToken.isValid(SECRET, "no-dot", 2, "alice", NOW)).isFalse();
        assertThat(AdmissionToken.isValid(SECRET, "%%%.%%%", 2, "alice", NOW)).isFalse();
    }
}
