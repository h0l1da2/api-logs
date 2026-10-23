package io.github.h0l1da2.apilog.store;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.ObjectMapper;

/**
 * 기록을 큐에 받아 가상 스레드 하나가 날짜별 jsonl 파일에 이어 쓴다.
 *
 * 책임:
 * - 요청 스레드는 큐에 넣고 바로 돌아간다. 큐가 차면 버리고 {@link #dropped()}를 올린다
 * - 최대 {@value #BATCH_SIZE}건씩 묶어 쓰고 묶음마다 flush 한다 (대기 최대 1초)
 * - 하루 파일이 {@code max-file-mb}를 넘으면 그날은 더 쓰지 않는다
 *
 * 주의:
 * - 로깅 프레임워크를 거치지 않고 파일에 직접 쓴다. 본문이 stdout(→ Alloy/Loki)으로 새면 안 된다
 * - 웹 서버보다 늦게 멈추도록 phase를 낮게 둔다 (마지막 요청까지 기록)
 */
public class ApiAccessLogWriter implements ApiAccessLogSink, SmartLifecycle {

    static final int BATCH_SIZE = 200;
    private static final Log log = LogFactory.getLog(ApiAccessLogWriter.class);

    private final ApiAccessLogProperties properties;
    private final ObjectMapper mapper;
    private final Path directory;
    private final BlockingQueue<ApiAccessLogRecord> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final long maxFileBytes;

    private volatile boolean running;
    private Thread worker;

    // 아래 필드는 worker 스레드만 만진다
    private LocalDate currentDate;
    private BufferedWriter out;
    private long currentSize;
    private boolean currentDateFull;
    private long lastErrorLoggedAt;

    public ApiAccessLogWriter(ApiAccessLogProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.directory = properties.directoryPath();
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
        this.maxFileBytes = properties.maxFileMb() * 1024L * 1024L;
    }

    @Override
    public void offer(ApiAccessLogRecord record) {
        if (!running || !queue.offer(record)) {
            dropped.incrementAndGet();
        }
    }

    /** 큐가 차거나 멈춘 상태라 버린 건수 (재시작하면 0). */
    public long dropped() {
        return dropped.get();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new IllegalStateException("api-access-log 폴더를 만들 수 없습니다: " + directory, e);
        }
        running = true;
        worker = Thread.ofVirtual().name("api-access-log-writer").start(this::runLoop);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            worker.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 웹 서버(DEFAULT_PHASE - 1024, -2048)보다 먼저 시작하고 나중에 멈춘다
        return SmartLifecycle.DEFAULT_PHASE - 4096;
    }

    private void runLoop() {
        final List<ApiAccessLogRecord> batch = new ArrayList<>(BATCH_SIZE);
        while (running || !queue.isEmpty()) {
            try {
                final ApiAccessLogRecord first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                writeBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                logErrorThrottled(e);
            } finally {
                batch.clear();
            }
        }
        closeQuietly();
    }

    private void writeBatch(List<ApiAccessLogRecord> batch) {
        try {
            for (ApiAccessLogRecord record : batch) {
                final LocalDate date = LogFiles.dateOfRecord(record.id(), properties.zone());
                openFor(date);
                if (currentDateFull) {
                    dropped.incrementAndGet();
                    continue;
                }
                final String line = mapper.writeValueAsString(record) + "\n";
                out.write(line);
                currentSize += line.getBytes(StandardCharsets.UTF_8).length;
                if (currentSize >= maxFileBytes) {
                    currentDateFull = true;
                    log.warn("api-access-log 하루 파일 크기 상한(" + properties.maxFileMb()
                            + "MB)에 도달해 " + date + " 기록을 중단합니다.");
                }
            }
            if (out != null) {
                out.flush();
            }
        } catch (IOException e) {
            logErrorThrottled(e);
            closeQuietly();
        }
    }

    private void openFor(LocalDate date) throws IOException {
        if (date.equals(currentDate) && out != null) {
            return;
        }
        closeQuietly();
        final Path file = LogFiles.fileFor(directory, date);
        final boolean created = !Files.exists(file);
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (created) {
            ownerOnly(file);
        }
        currentDate = date;
        currentSize = Files.size(file);
        currentDateFull = currentSize >= maxFileBytes;
    }

    private static void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX 권한을 지원하지 않는 파일 시스템(Windows 등)
        }
    }

    private void closeQuietly() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 닫기 실패는 다음 쓰기에서 다시 연다
            }
            out = null;
        }
        currentDate = null;
    }

    private void logErrorThrottled(Exception e) {
        final long now = System.currentTimeMillis();
        if (now - lastErrorLoggedAt > 60_000) {
            lastErrorLoggedAt = now;
            // 기록 내용(본문)은 남기지 않고 예외 종류만 남긴다
            log.warn("api-access-log 파일 쓰기 실패: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }
}
