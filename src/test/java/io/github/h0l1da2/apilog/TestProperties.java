package io.github.h0l1da2.apilog;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 테스트에서 실제 바인딩 규칙(@DefaultValue 포함)대로 설정 객체를 만든다. */
public final class TestProperties {

    private TestProperties() {
    }

    public static ApiAccessLogProperties of(String... keyValues) {
        final Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put("api-access-log." + keyValues[i], keyValues[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(map))
                .bindOrCreate("api-access-log", ApiAccessLogProperties.class);
    }
}
