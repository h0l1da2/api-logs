# api-logs

Spring Boot 앱의 API 요청·응답을 **jsonl 파일**로 기록하고, 조회 API와 확인용 화면을 제공하는 자동 설정 라이브러리입니다.
의존성을 추가하면 별도 코드 없이 동작합니다.

## 요구 사항

- Java 21 이상
- Spring Boot 4.x (Spring MVC, Jackson 3)

## 설치

```groovy
dependencies {
    implementation 'io.github.h0l1da2:api-logs:0.1.0'
}
```

```xml
<dependency>
    <groupId>io.github.h0l1da2</groupId>
    <artifactId>api-logs</artifactId>
    <version>0.1.0</version>
</dependency>
```

## 설정 (`application.yml`)

모두 선택 사항이며 아래는 기본값입니다.

```yaml
api-access-log:
  enabled: true
  base-path: /api-logs            # 화면: /api-logs, 조회 API: /api-logs/api/**
  directory: ./logs/api-access    # jsonl 저장 폴더
  retention-days: 2
  include-paths: [/api/**]
  exclude-paths: [/actuator/**, /api-logs/**]
  max-body-bytes: 1048576         # 요청·응답 본문 각각의 상한
  max-error-bytes: 2048
  record-headers: false
  queue-capacity: 10000
  max-file-mb: 200
  zone: Asia/Seoul
  request-id-header: X-Request-Id
  # mask-keys / mask-patterns 를 지정하면 기본 목록을 대체합니다 (추가가 아님)
```

## 보안

라이브러리는 화면과 조회 API(`base-path` 아래)에 보안을 걸지 않습니다. **반드시 앱에서 Spring Security 등으로 보호**하세요.
`api-access-log.username`, `api-access-log.password`는 앱이 같은 접두어로 읽어 쓰도록 남겨둔 값입니다.

## 요청자(caller) 기록

기본은 Spring Security의 `SecurityContext`에서 읽습니다. 바꾸려면 `CallerResolver` 빈을 등록하세요.

```java
@Bean
CallerResolver callerResolver() {
    return request -> /* id·역할 등, 개인정보는 넣지 않기 */ "user-123";
}
```

## 라이선스

[Apache License 2.0](LICENSE)
