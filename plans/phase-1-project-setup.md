# Phase 1: 프로젝트 기반 설정

## 목표
- build.gradle 의존성 완성
- application.yaml 설정
- common 레이어 전체 구현 (예외, 응답 포맷, 동시성 인프라, 멱등성 인프라)

---

## 1. build.gradle

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.0.5'
    id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.hamlsy'
version = '0.0.1-SNAPSHOT'

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-h2console'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-restclient'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'com.github.ben-manes.caffeine:caffeine'
    compileOnly 'org.projectlombok:lombok'
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    runtimeOnly 'com.h2database:h2'
    annotationProcessor 'org.projectlombok:lombok'
    testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-restclient-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
    testCompileOnly 'org.projectlombok:lombok'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    testAnnotationProcessor 'org.projectlombok:lombok'
}

tasks.named('test') {
    useJUnitPlatform()
}
```

---

## 2. application.yaml

```yaml
spring:
  application:
    name: liveklass-assignment

  datasource:
    url: jdbc:h2:mem:liveklass;DB_CLOSE_DELAY=-1;MODE=MySQL
    driver-class-name: org.h2.Driver
    username: sa
    password:
    hikari:
      maximum-pool-size: 10        # Semaphore permits 설정 기준값
      minimum-idle: 5
      connection-timeout: 3000     # Connection 획득 대기 최대 3초
      idle-timeout: 600000
      max-lifetime: 1800000

  h2:
    console:
      enabled: true
      path: /h2-console

  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true
    open-in-view: false            # OSIV 비활성화 — LazyLoading 범위를 트랜잭션으로 제한
    properties:
      hibernate:
        format_sql: true
        default_batch_fetch_size: 100

  security:
    user:
      name: admin
      password: admin
```

> `open-in-view: false`: Controller/View 계층에서 LazyLoading 불가 → UseCase에서 명시적 로딩 강제.
> N+1 실수를 런타임에 즉시 발견할 수 있다.

---

## 3. common 패키지 구조

```
common/
├── config/
│   ├── CacheConfig.java            ← Caffeine 캐시 Bean 정의
│   └── SecurityConfig.java
├── exception/
│   ├── BusinessException.java
│   ├── ErrorCode.java
│   ├── GlobalExceptionHandler.java
│   ├── CourseNotFoundException.java
│   ├── EnrollmentNotFoundException.java
│   ├── EnrollmentLimitExceededException.java
│   ├── InvalidCourseStatusException.java
│   ├── AlreadyEnrolledException.java
│   ├── CancellationPeriodExpiredException.java
│   └── EnrollmentQueueFullException.java
├── response/
│   └── ErrorResponse.java
├── concurrency/
│   ├── EnrollmentSemaphoreManager.java
│   └── CourseCapacityInfo.java
└── idempotency/
    ├── IdempotencyInterceptor.java  ← 멱등성 키 처리
    └── IdempotencyResponse.java     ← 캐시 저장용 래퍼
```

---

## 4. ErrorCode (Enum)

```java
package com.hamlsy.liveklass_assignment.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Course
    COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "강의를 찾을 수 없습니다."),
    INVALID_COURSE_STATUS(HttpStatus.BAD_REQUEST, "수강 신청이 불가한 강의 상태입니다."),
    INVALID_ENROLLMENT_PERIOD(HttpStatus.BAD_REQUEST, "수강 신청 기간이 아닙니다."),
    INVALID_COURSE_DATE(HttpStatus.BAD_REQUEST, "종료일은 시작일 이후여야 합니다."),
    INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "허용되지 않는 상태 전이입니다."),

    // Enrollment
    ENROLLMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "수강 신청 내역을 찾을 수 없습니다."),
    ENROLLMENT_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "정원이 초과되었습니다."),
    ALREADY_ENROLLED(HttpStatus.BAD_REQUEST, "이미 신청한 강의입니다."),
    CANCELLATION_PERIOD_EXPIRED(HttpStatus.BAD_REQUEST, "취소 가능 기간(7일)이 지났습니다."),
    INVALID_ENROLLMENT_STATUS(HttpStatus.BAD_REQUEST, "현재 상태에서는 해당 작업을 수행할 수 없습니다."),
    UNAUTHORIZED_ENROLLMENT(HttpStatus.FORBIDDEN, "본인의 수강 신청만 처리할 수 있습니다."),

    // Concurrency
    ENROLLMENT_QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "현재 요청이 많습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String message;
}
```

---

## 5. BusinessException + 도메인 예외

```java
// BusinessException.java — 모든 도메인 예외의 부모
@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}

// 각 도메인 예외 (패키지: common.exception)
public class CourseNotFoundException extends BusinessException {
    public CourseNotFoundException() { super(ErrorCode.COURSE_NOT_FOUND); }
}
public class EnrollmentNotFoundException extends BusinessException {
    public EnrollmentNotFoundException() { super(ErrorCode.ENROLLMENT_NOT_FOUND); }
}
public class EnrollmentLimitExceededException extends BusinessException {
    public EnrollmentLimitExceededException() { super(ErrorCode.ENROLLMENT_LIMIT_EXCEEDED); }
}
public class InvalidCourseStatusException extends BusinessException {
    public InvalidCourseStatusException() { super(ErrorCode.INVALID_COURSE_STATUS); }
    public InvalidCourseStatusException(ErrorCode code) { super(code); }  // 기간 외 등 세부 코드용
}
public class AlreadyEnrolledException extends BusinessException {
    public AlreadyEnrolledException() { super(ErrorCode.ALREADY_ENROLLED); }
}
public class CancellationPeriodExpiredException extends BusinessException {
    public CancellationPeriodExpiredException() { super(ErrorCode.CANCELLATION_PERIOD_EXPIRED); }
}
public class EnrollmentQueueFullException extends BusinessException {
    public EnrollmentQueueFullException() { super(ErrorCode.ENROLLMENT_QUEUE_FULL); }
}
```

---

## 6. ErrorResponse

```java
package com.hamlsy.liveklass_assignment.common.response;

import com.hamlsy.liveklass_assignment.common.exception.ErrorCode;

public record ErrorResponse(String code, String message) {
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }
}
```

---

## 7. GlobalExceptionHandler

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        log.warn("BusinessException: {}", e.getMessage());
        return ResponseEntity
            .status(e.getErrorCode().getHttpStatus())
            .body(ErrorResponse.of(e.getErrorCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return ResponseEntity.internalServerError()
            .body(new ErrorResponse("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."));
    }
}
```

---

## 8. CacheConfig

```java
package com.hamlsy.liveklass_assignment.common.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hamlsy.liveklass_assignment.common.concurrency.CourseCapacityInfo;
import com.hamlsy.liveklass_assignment.common.idempotency.IdempotencyResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /**
     * 수강 신청 사전 필터링용 캐시
     * TTL 5초: 짧게 유지하여 stale 오탐 최소화
     * 실제 정합성은 Pessimistic Lock이 보장
     */
    @Bean
    public Cache<Long, CourseCapacityInfo> courseCapacityCache() {
        return Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 멱등성 키 → 응답 캐시
     * TTL 24시간: 클라이언트 재시도 윈도우
     * maximumSize 10_000: 동시 활성 요청 수 상한 가정
     */
    @Bean
    public Cache<String, IdempotencyResponse> idempotencyCache() {
        return Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();
    }
}
```

---

## 9. CourseCapacityInfo

```java
package com.hamlsy.liveklass_assignment.common.concurrency;

import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import java.time.LocalDateTime;

public record CourseCapacityInfo(
    CourseStatus status,
    int capacity,
    int currentCount,
    LocalDateTime startDate,
    LocalDateTime endDate
) {
    /** 캐시 기준 정원 초과 여부. 실제 정합성은 Lock 보장. */
    public boolean isLikelyFull() { return currentCount >= capacity; }

    public boolean isNotOpen() { return status != CourseStatus.OPEN; }

    public boolean isOutOfPeriod() {
        LocalDateTime now = LocalDateTime.now();
        return now.isBefore(startDate) || now.isAfter(endDate);
    }

    public static CourseCapacityInfo from(
            com.hamlsy.liveklass_assignment.course.domain.entity.Course course) {
        return new CourseCapacityInfo(
            course.getStatus(), course.getCapacity(), course.getCurrentCount(),
            course.getStartDate(), course.getEndDate()
        );
    }
}
```

---

## 10. EnrollmentSemaphoreManager

```java
package com.hamlsy.liveklass_assignment.common.concurrency;

import com.hamlsy.liveklass_assignment.common.exception.EnrollmentQueueFullException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class EnrollmentSemaphoreManager {

    /**
     * MAX_CONCURRENT_PER_COURSE = hikari.maximum-pool-size / 2 (기본값 5)
     * → Pool의 절반만 수강 신청에 할당, 나머지는 조회 등 다른 요청용으로 예약
     *
     * fair = true: 먼저 대기한 스레드가 먼저 진입 (선착순 의미 부여)
     *
     * [메모리 누수 방지]
     * Semaphore는 강의가 CLOSED되거나 서비스 재시작 시 정리됨.
     * 운영 환경에서는 강의 수가 유한하므로 Map 크기가 제한됨.
     * 필요 시 UpdateCourseStatusUseCase에서 CLOSED 전환 시 remove() 호출.
     */
    private static final int MAX_CONCURRENT_PER_COURSE = 5;
    private static final long ACQUIRE_TIMEOUT_SECONDS = 2L;

    private final ConcurrentHashMap<Long, Semaphore> semaphores = new ConcurrentHashMap<>();

    public void acquire(Long courseId) {
        Semaphore semaphore = semaphores.computeIfAbsent(
            courseId,
            id -> new Semaphore(MAX_CONCURRENT_PER_COURSE, true)
        );
        try {
            boolean acquired = semaphore.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new EnrollmentQueueFullException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EnrollmentQueueFullException();
        }
    }

    public void release(Long courseId) {
        Semaphore semaphore = semaphores.get(courseId);
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /**
     * 강의가 CLOSED될 때 호출하여 Semaphore 제거 (메모리 누수 방지)
     * UpdateCourseStatusUseCase에서 status == CLOSED 전환 시 호출
     */
    public void removeSemaphore(Long courseId) {
        semaphores.remove(courseId);
    }
}
```

---

## 11. 멱등성(Idempotency) 인프라

### IdempotencyResponse.java

```java
package com.hamlsy.liveklass_assignment.common.idempotency;

import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;

/**
 * 멱등성 캐시 저장 단위
 * enrollmentId: 이미 처리된 Enrollment ID (재조회용)
 * response: 클라이언트에 반환할 직렬화된 응답
 */
public record IdempotencyResponse(
    Long enrollmentId,
    EnrollmentResponse response
) {}
```

### IdempotencyInterceptor.java

```java
package com.hamlsy.liveklass_assignment.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * POST /enrollments 멱등성 처리 인터셉터
 *
 * 클라이언트는 요청 헤더에 Idempotency-Key (UUID)를 포함해야 한다.
 * 동일 키로 재요청 시 캐시된 응답을 그대로 반환 (DB 재처리 없음).
 *
 * 적용 범위: POST /enrollments 만 (WebMvcConfig에서 경로 지정)
 *
 * 처리 흐름:
 *   1. 헤더에서 Idempotency-Key 추출
 *   2. 캐시 조회
 *      - Hit: 캐시된 응답을 201로 반환, 컨트롤러 실행 중단
 *      - Miss: 컨트롤러 실행 진행, 응답을 캐시에 저장 (postHandle에서)
 *
 * 주의: 키가 없으면 정상 처리 (키는 선택 사항으로 운영)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENCY_KEY_ATTR = "idempotencyKey";

    private final Cache<String, IdempotencyResponse> idempotencyCache;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String key = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (key == null || key.isBlank()) {
            return true;  // 키 없음 → 정상 처리
        }

        IdempotencyResponse cached = idempotencyCache.getIfPresent(key);
        if (cached != null) {
            // 캐시 hit: 저장된 응답 반환, 컨트롤러 실행 skip
            log.debug("Idempotency cache hit: key={}, enrollmentId={}", key, cached.enrollmentId());
            response.setStatus(HttpStatus.CREATED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(cached.response()));
            return false;  // 컨트롤러 실행 중단
        }

        // 캐시 miss: 키를 request attribute에 저장 (postHandle에서 캐시 저장에 사용)
        request.setAttribute(IDEMPOTENCY_KEY_ATTR, key);
        return true;
    }
}
```

### WebMvcConfig.java

```java
package com.hamlsy.liveklass_assignment.common.config;

import com.hamlsy.liveklass_assignment.common.idempotency.IdempotencyInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final IdempotencyInterceptor idempotencyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyInterceptor)
            .addPathPatterns("/enrollments")  // POST /enrollments 만 적용
            .order(1);
    }
}
```

---

## 12. SecurityConfig

```java
package com.hamlsy.liveklass_assignment.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
```

---

## 완료 체크리스트

- [ ] build.gradle — caffeine 의존성
- [ ] application.yaml — hikari, jpa, h2, open-in-view=false
- [ ] ErrorCode enum
- [ ] BusinessException + 도메인 예외 7종
- [ ] ErrorResponse
- [ ] GlobalExceptionHandler
- [ ] CacheConfig (courseCapacityCache + idempotencyCache)
- [ ] CourseCapacityInfo (from(Course) 팩토리 메서드 포함)
- [ ] EnrollmentSemaphoreManager (removeSemaphore 포함)
- [ ] IdempotencyResponse
- [ ] IdempotencyInterceptor
- [ ] WebMvcConfig (인터셉터 등록)
- [ ] SecurityConfig
