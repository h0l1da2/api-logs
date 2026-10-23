package io.github.h0l1da2.apilog.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.h0l1da2.apilog.TestProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("보관 기간이 지난 파일 삭제")
class RetentionCleanerTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("보관 일수 2일이면 오늘과 어제만 남기고 규칙에 맞지 않는 파일은 건드리지 않는다")
    void keepsTodayAndYesterday() throws Exception {
        final LocalDate today = LocalDate.of(2026, 10, 22);
        for (int daysAgo : new int[] {0, 1, 2, 10}) {
            Files.writeString(LogFiles.fileFor(directory, today.minusDays(daysAgo)), "{}\n");
        }
        final Path unrelated = Files.writeString(directory.resolve("app.log"), "keep");

        final RetentionCleaner cleaner = new RetentionCleaner(
                TestProperties.of("directory", directory.toString(), "retention-days", "2"));
        cleaner.clean(today);

        assertThat(LogFiles.fileFor(directory, today)).exists();
        assertThat(LogFiles.fileFor(directory, today.minusDays(1))).exists();
        assertThat(LogFiles.fileFor(directory, today.minusDays(2))).doesNotExist();
        assertThat(LogFiles.fileFor(directory, today.minusDays(10))).doesNotExist();
        assertThat(unrelated).exists();
    }
}
