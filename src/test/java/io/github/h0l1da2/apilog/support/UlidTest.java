package io.github.h0l1da2.apilog.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ULID")
class UlidTest {

    @Test
    @DisplayName("26자이고 앞부분에서 생성 시각을 되찾을 수 있다")
    void roundTripsTimestamp() {
        final long millis = 1_792_644_375_705L;
        final String id = Ulid.generate(millis);
        assertThat(id).hasSize(26);
        assertThat(Ulid.timestamp(id)).isEqualTo(millis);
    }

    @Test
    @DisplayName("문자열 정렬 순서가 시간 순서와 같다")
    void sortsByTime() {
        final String earlier = Ulid.generate(1_000_000L);
        final String later = Ulid.generate(1_000_001L);
        assertThat(earlier).isLessThan(later);
    }

    @Test
    @DisplayName("형식이 틀리면 거부한다")
    void rejectsInvalidId() {
        assertThatThrownBy(() -> Ulid.timestamp("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ulid.timestamp("UUUUUUUUUUUUUUUUUUUUUUUUUU")).isInstanceOf(IllegalArgumentException.class);
    }
}
