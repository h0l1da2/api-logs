package io.github.h0l1da2.apilog.capture;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 저장 전에 본문·쿼리·헤더·예외 메시지의 민감한 값을 가린다.
 *
 * 규칙:
 * - 키 마스킹: {@code mask-keys}와 이름이 정확히 같은 JSON 필드·폼 파라미터 값을 {@value #MASK}로 바꾼다
 * - 값 패턴 마스킹: {@code mask-patterns}에 맞는 부분 문자열을 {@value #MASK}로 바꾼다 (키를 모르는 값 대비)
 * - JSON이 잘려 파싱되지 않으면 정규식으로 {@code "key":"value"} 형태를 찾아 가린다
 *
 * 주의:
 * - 이 클래스는 마스킹 결과를 로그로 출력하지 않는다
 */
public class BodyMasker {

    public static final String MASK = "***";

    private static final Set<String> ALWAYS_MASKED_HEADERS =
            Set.of("authorization", "proxy-authorization", "cookie", "set-cookie");

    private final ObjectMapper mapper;
    private final Set<String> maskKeys;
    private final List<Pattern> valuePatterns;
    private final Pattern jsonKeyPattern;

    public BodyMasker(ApiAccessLogProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.maskKeys = Set.copyOf(properties.maskKeys());
        this.valuePatterns = properties.maskPatterns().stream().map(Pattern::compile).toList();
        final String keys = maskKeys.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        // "key" : "value"  /  "key" : 123  형태. 잘린 JSON에서도 동작한다
        this.jsonKeyPattern = Pattern.compile("\"(" + keys + ")\"(\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"?|[^,}\\]\\s]+)");
    }

    public String maskBody(String body, boolean json, boolean form) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        if (json) {
            return maskValues(maskJson(body));
        }
        if (form) {
            return maskValues(maskForm(body));
        }
        return maskValues(body);
    }

    public String maskQuery(String query) {
        return query == null ? null : maskValues(maskForm(query));
    }

    public String maskValues(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Pattern pattern : valuePatterns) {
            result = pattern.matcher(result).replaceAll(MASK);
        }
        return result;
    }

    public Map<String, String> maskHeaders(Map<String, String> headers) {
        return headers.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> isMaskedHeader(entry.getKey()) ? MASK : maskValues(entry.getValue()),
                (a, b) -> a,
                java.util.LinkedHashMap::new));
    }

    private boolean isMaskedHeader(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return ALWAYS_MASKED_HEADERS.contains(lower) || maskKeys.contains(name);
    }

    private String maskJson(String body) {
        try {
            final JsonNode root = mapper.readTree(body);
            if (root == null || !(root.isObject() || root.isArray())) {
                return maskJsonByRegex(body);
            }
            maskNode(root);
            return mapper.writeValueAsString(root);
        } catch (RuntimeException e) {
            // 잘린 JSON 등 파싱 실패 → 정규식으로 처리
            return maskJsonByRegex(body);
        }
    }

    private void maskNode(JsonNode node) {
        if (node.isObject()) {
            final ObjectNode object = (ObjectNode) node;
            final List<String> names = object.properties().stream().map(Map.Entry::getKey).toList();
            for (String name : names) {
                final JsonNode child = object.get(name);
                if (maskKeys.contains(name) && child != null && !child.isNull()) {
                    object.put(name, MASK);
                } else if (child != null) {
                    maskNode(child);
                }
            }
        } else if (node.isArray()) {
            final ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                maskNode(array.get(i));
            }
        }
    }

    private String maskJsonByRegex(String body) {
        if (maskKeys.isEmpty()) {
            return body;
        }
        final Matcher matcher = jsonKeyPattern.matcher(body);
        final StringBuilder out = new StringBuilder(body.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    "\"" + matcher.group(1) + "\"" + matcher.group(2) + "\"" + MASK + "\""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String maskForm(String form) {
        final String[] pairs = form.split("&", -1);
        final StringBuilder out = new StringBuilder(form.length());
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                out.append('&');
            }
            final String pair = pairs[i];
            final int eq = pair.indexOf('=');
            final String key = eq < 0 ? pair : pair.substring(0, eq);
            if (eq >= 0 && maskKeys.contains(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                out.append(key).append('=').append(MASK);
            } else {
                out.append(pair);
            }
        }
        return out.toString();
    }
}
