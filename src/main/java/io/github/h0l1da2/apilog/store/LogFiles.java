package io.github.h0l1da2.apilog.store;

import io.github.h0l1da2.apilog.support.Ulid;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 날짜별 jsonl 파일 이름 규칙(api-access-YYYY-MM-DD.jsonl)을 한곳에 둔다. writer·reader·cleaner가 같이 쓴다.
 */
public final class LogFiles {

    private static final String PREFIX = "api-access-";
    private static final String SUFFIX = ".jsonl";
    private static final Pattern NAME = Pattern.compile("api-access-(\\d{4}-\\d{2}-\\d{2})\\.jsonl");

    private LogFiles() {
    }

    public static Path fileFor(Path directory, LocalDate date) {
        return directory.resolve(PREFIX + date + SUFFIX);
    }

    /** 파일 이름에서 날짜를 읽는다. 규칙에 맞지 않는 파일이면 빈 값. */
    public static Optional<LocalDate> dateOf(Path file) {
        final Matcher matcher = NAME.matcher(file.getFileName().toString());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(matcher.group(1)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** 기록 id(ULID)의 시각으로 어느 날짜 파일에 들어가는지 정한다. */
    public static LocalDate dateOfRecord(String recordId, ZoneId zone) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(Ulid.timestamp(recordId)), zone);
    }
}
