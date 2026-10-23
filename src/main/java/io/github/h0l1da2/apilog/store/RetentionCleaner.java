package io.github.h0l1da2.apilog.store;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 보관 기간({@code retention-days}, 오늘 포함)이 지난 jsonl 파일을 지운다.
 *
 * 설계 의도:
 * - 앱의 @EnableScheduling 여부와 상관없이 동작하도록 자체 스케줄러를 쓴다
 * - 시작 시 1회, 이후 매일 00:05(설정한 zone 기준)에 돈다
 */
public class RetentionCleaner implements SmartLifecycle {

    private static final Log log = LogFactory.getLog(RetentionCleaner.class);
    private static final LocalTime DAILY_AT = LocalTime.of(0, 5);

    private final ApiAccessLogProperties properties;
    private final Path directory;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public RetentionCleaner(ApiAccessLogProperties properties) {
        this.properties = properties;
        this.directory = properties.directoryPath();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("api-access-log-cleaner").factory());
        scheduler.execute(this::cleanQuietly);
        final ZonedDateTime now = ZonedDateTime.now(properties.zone());
        ZonedDateTime next = now.toLocalDate().atTime(DAILY_AT).atZone(properties.zone());
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        scheduler.scheduleAtFixedRate(this::cleanQuietly,
                Duration.between(now, next).toMillis(), TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        scheduler.shutdownNow();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 4096;
    }

    /** 오늘 기준 보관 기간이 지난 파일을 지우고, 지운 파일 목록을 돌려준다. */
    public List<Path> clean(LocalDate today) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        final LocalDate oldestKept = today.minusDays(properties.retentionDays() - 1L);
        try (Stream<Path> files = Files.list(directory)) {
            final List<Path> expired = files
                    .filter(file -> LogFiles.dateOf(file).map(date -> date.isBefore(oldestKept)).orElse(false))
                    .toList();
            for (Path file : expired) {
                Files.deleteIfExists(file);
            }
            return expired;
        }
    }

    private void cleanQuietly() {
        try {
            final List<Path> deleted = clean(LocalDate.now(properties.zone()));
            if (!deleted.isEmpty()) {
                log.info("api-access-log 보관 기간이 지난 파일 " + deleted.size() + "개를 지웠습니다.");
            }
        } catch (IOException | RuntimeException e) {
            log.warn("api-access-log 오래된 파일 삭제 실패: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }
}
