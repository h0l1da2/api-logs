package io.github.h0l1da2.apilog.capture;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import io.github.h0l1da2.apilog.BodyPolicy;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.AntPathMatcher;

/**
 * 어떤 요청을 기록할지, 본문을 어디까지 남길지 판단한다.
 *
 * 책임:
 * - include/exclude 경로 판별
 * - route 패턴별 본문 정책({@link BodyPolicy}) 조회
 * - 본문을 텍스트로 다뤄도 되는 Content-Type인지 판별
 */
public class CapturePolicy {

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final List<String> includePaths;
    private final List<String> excludePaths;
    private final Map<String, BodyPolicy> bodyPolicies;

    public CapturePolicy(ApiAccessLogProperties properties) {
        this.includePaths = List.copyOf(properties.includePaths());
        this.excludePaths = List.copyOf(properties.excludePaths());
        this.bodyPolicies = properties.bodyPolicies();
    }

    public boolean shouldRecord(String path) {
        return includePaths.stream().anyMatch(pattern -> matcher.match(pattern, path))
                && excludePaths.stream().noneMatch(pattern -> matcher.match(pattern, path));
    }

    /** route(매핑 패턴)가 있으면 route로, 없으면 실제 경로로 정책을 찾는다. 지정이 없으면 FULL. */
    public BodyPolicy bodyPolicyFor(String route, String path) {
        final String target = route != null ? route : path;
        return bodyPolicies.entrySet().stream()
                .filter(entry -> matcher.match(entry.getKey(), target) || matcher.match(entry.getKey(), path))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(BodyPolicy.FULL);
    }

    public boolean isTextual(String contentType) {
        if (contentType == null) {
            return false;
        }
        final String type = contentType.toLowerCase(Locale.ROOT);
        return type.contains("json")
                || type.startsWith("text/")
                || type.contains("xml")
                || type.startsWith("application/x-www-form-urlencoded");
    }

    public boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
    }

    public boolean isForm(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    public boolean isMultipart(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }

    public boolean isEventStream(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/event-stream");
    }
}
