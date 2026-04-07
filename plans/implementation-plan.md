# LiveKlass 수강 신청 시스템 구현 계획 (v2)

---

## 1. 패키지 구조

```
com.hamlsy.liveklass_assignment/
│
├── member/
│   ├── domain/
│   │   ├── entity/
│   │   │   └── Member.java
│   │   └── repository/
│   │       └── MemberRepository.java
│   └── infrastructure/
│       └── MemberJpaRepository.java
│
├── course/
│   ├── presentation/
│   │   ├── CourseController.java
│   │   ├── request/
│   │   │   ├── CreateCourseRequest.java
│   │   │   └── UpdateCourseStatusRequest.java
│   │   └── response/
│   │       ├── CourseDetailResponse.java
│   │       └── CourseListResponse.java
│   ├── application/
│   │   ├── service/
│   │   │   └── CourseService.java          ← 도메인 로직 위임 + 트랜잭션
│   │   └── usecase/
│   │       ├── CreateCourseUseCase.java
│   │       ├── UpdateCourseStatusUseCase.java
│   │       ├── GetCourseListUseCase.java
│   │       └── GetCourseDetailUseCase.java
│   ├── domain/
│   │   ├── entity/
│   │   │   ├── Course.java
│   │   │   └── CourseStatus.java           (Enum)
│   │   ├── repository/
│   │   │   └── CourseRepository.java
│   │   └── service/
│   │       └── CourseDomainService.java    ← 도메인 규칙 (필요시)
│   └── infrastructure/
│       └── CourseJpaRepository.java
│
├── enrollment/
│   ├── presentation/
│   │   ├── EnrollmentController.java
│   │   ├── request/
│   │   │   └── CreateEnrollmentRequest.java
│   │   └── response/
│   │       └── EnrollmentResponse.java
│   ├── application/
│   │   ├── service/
│   │   │   └── EnrollmentService.java      ← 도메인 로직 위임 + 트랜잭션
│   │   └── usecase/
│   │       ├── CreateEnrollmentUseCase.java
│   │       ├── ConfirmPaymentUseCase.java
│   │       ├── CancelEnrollmentUseCase.java
│   │       └── GetMyEnrollmentsUseCase.java
│   ├── domain/
│   │   ├── entity/
│   │   │   ├── Enrollment.java
│   │   │   └── EnrollmentStatus.java       (Enum)
│   │   ├── repository/
│   │   │   └── EnrollmentRepository.java
│   │   └── service/
│   │       └── EnrollmentDomainService.java ← 중복 신청 검증 등
│   └── infrastructure/
│       └── EnrollmentJpaRepository.java
│
└── common/
    ├── exception/
    │   ├── GlobalExceptionHandler.java
    │   ├── CourseNotFoundException.java
    │   ├── EnrollmentLimitExceededException.java
    │   ├── InvalidCourseStatusException.java
    │   ├── AlreadyEnrolledException.java
    │   └── CancellationPeriodExpiredException.java
    ├── response/
    │   └── ErrorResponse.java
    └── concurrency/
        └── EnrollmentSemaphoreManager.java ← Connection Pool 고갈 방지
```

---

## 2. 레이어 역할 구분

| 레이어 | 역할 |
|---|---|
| `application/usecase` | 오케스트레이션만. 여러 Service 호출, 흐름 제어 |
| `application/service` | 트랜잭션 경계, Repository 호출, 도메인 메서드 위임 |
| `domain/entity` | 비즈니스 규칙 및 상태 전이 로직 |
| `domain/repository` | Repository 인터페이스 선언 |
| `domain/service` | 단일 Entity로 처리하기 어려운 도메인 규칙 |
| `infrastructure` | JPA 구현체, @Lock, @Query |

---

## 3. 도메인 엔티티 설계

### 3.1 Course Entity

```java
@Entity
@Table(name = "course", indexes = {
    @Index(name = "idx_course_status", columnList = "status")
})
public class Course {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String description;
    private int price;
    private int capacity;
    private int currentCount;

    @Enumerated(EnumType.STRING)
    private CourseStatus status;  // DRAFT, OPEN, CLOSED

    private LocalDateTime startDate;
    private LocalDateTime endDate;
}
```

**도메인 메서드**:
- `validateOpen()` — OPEN이 아니면 InvalidCourseStatusException
- `validateEnrollmentPeriod()` — 현재 시간이 startDate~endDate 사이인지 검증
- `increaseCurrentCount()` — currentCount >= capacity 이면 EnrollmentLimitExceededException, 아니면 +1
- `decreaseCurrentCount()` — currentCount -1
- `changeStatus(CourseStatus next)` — CLOSED → OPEN 불가 검증 후 상태 변경

### 3.2 Enrollment Entity

```java
@Entity
@Table(name = "enrollment", indexes = {
    @Index(name = "idx_enrollment_user_id", columnList = "user_id"),
    @Index(name = "idx_enrollment_course_id", columnList = "course_id"),
    @Index(name = "idx_enrollment_user_course_status",
           columnList = "user_id, course_id, status")   // 중복 신청 검증 쿼리 최적화
})
public class Enrollment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private Course course;

    @Enumerated(EnumType.STRING)
    private EnrollmentStatus status;  // PENDING, CONFIRMED, CANCELLED

    private LocalDateTime enrolledAt;
    private LocalDateTime confirmedAt;
}
```

**도메인 메서드**:
- `confirm()` — PENDING 검증 → CONFIRMED, confirmedAt = now()
- `cancel(Long requestUserId)` — 소유자 검증, CONFIRMED 검증, 7일 이내 검증, CANCELLED 변경
- `isOwnedBy(Long userId)` — 소유자 여부

---

## 4. Repository 설계 및 N+1 방지

### CourseRepository (Interface)

```java
public interface CourseRepository {
    Course save(Course course);
    Optional<Course> findById(Long id);
    List<Course> findAllByStatus(CourseStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    Optional<Course> findByIdWithLock(Long id);
}
```

> `lock.timeout = 3000ms` — Lock 대기가 3초를 초과하면 즉시 예외 반환.
> 무한 대기로 인한 Connection 점유를 차단한다.

### EnrollmentRepository (Interface)

```java
public interface EnrollmentRepository {
    Enrollment save(Enrollment enrollment);
    Optional<Enrollment> findById(Long id);

    // 중복 신청 검증 — idx_enrollment_user_course_status 인덱스 활용
    boolean existsByUserIdAndCourseIdAndStatusIn(
        Long userId, Long courseId, List<EnrollmentStatus> statuses
    );

    // N+1 방지 — course를 JOIN FETCH로 함께 로딩
    @Query("SELECT e FROM Enrollment e JOIN FETCH e.course WHERE e.userId = :userId")
    List<Enrollment> findAllByUserIdWithCourse(@Param("userId") Long userId);
}
```

> `findAllByUserId`를 단순 사용하면 enrollment 목록 조회 후 각 `enrollment.getCourse()` 호출 시
> 강의 수만큼 SELECT가 추가 발생한다. `JOIN FETCH`로 단일 쿼리로 해결한다.

---

## 5. 동시성 전략 (Connection Pool 고갈 방지)

### 5.1 문제 정의

Pessimistic Lock을 잡은 트랜잭션이 길어지면:
- 다른 스레드들이 Lock을 기다리며 DB Connection을 점유 상태로 유지
- Connection Pool(기본 10개)이 고갈되면 전체 서비스 중단

### 5.2 해결책: Semaphore + Caffeine 조합

**두 가지 방어선**을 둔다.

```
요청 진입
   │
   ▼
[1단계] Caffeine Cache로 빠른 사전 검증
   │  (status, 기간, 잔여석 캐시 확인)
   │  → 명백히 실패할 요청은 DB 접근 없이 즉시 거절
   │
   ▼
[2단계] 강의별 Semaphore로 동시 진입 제한
   │  → tryAcquire(2s) 실패 시 즉시 503 반환
   │  → DB Connection 점유 스레드 수를 제어
   │
   ▼
[3단계] 트랜잭션 내 Pessimistic Lock
   │  lock.timeout = 3000ms
   │  → 정확한 정합성 보장
   ▼
결과 반환 → Cache Evict
```

### 5.3 EnrollmentSemaphoreManager

```java
@Component
public class EnrollmentSemaphoreManager {

    // 강의별 Semaphore. 강의 ID → Semaphore
    // permits = Connection Pool size / 예상 동시 강의 수
    private final ConcurrentHashMap<Long, Semaphore> semaphores = new ConcurrentHashMap<>();
    private static final int MAX_CONCURRENT_PER_COURSE = 5;  // Connection Pool 크기에 맞게 조정

    public Semaphore getSemaphore(Long courseId) {
        return semaphores.computeIfAbsent(courseId,
            id -> new Semaphore(MAX_CONCURRENT_PER_COURSE, true));  // fair=true: 순서 보장
    }

    public void tryAcquire(Long courseId) {
        Semaphore semaphore = getSemaphore(courseId);
        try {
            boolean acquired = semaphore.tryAcquire(2, TimeUnit.SECONDS);
            if (!acquired) {
                throw new EnrollmentQueueFullException("현재 요청이 많습니다. 잠시 후 다시 시도해주세요.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EnrollmentQueueFullException("요청이 중단되었습니다.");
        }
    }

    public void release(Long courseId) {
        getSemaphore(courseId).release();
    }
}
```

### 5.4 Caffeine Cache (사전 검증용)

```java
@Configuration
public class CacheConfig {

    @Bean
    public Cache<Long, CourseCapacityInfo> courseCapacityCache() {
        return Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.SECONDS)  // 짧게 설정: 정합성보다 필터링 목적
            .build();
    }
}

// 캐시 데이터 구조
public record CourseCapacityInfo(
    CourseStatus status,
    int capacity,
    int currentCount,
    LocalDateTime startDate,
    LocalDateTime endDate
) {
    public boolean isLikelyFull() {
        return currentCount >= capacity;
    }

    public boolean isOpen() {
        return status == CourseStatus.OPEN;
    }
}
```

> 캐시는 사전 필터링(명백히 실패할 요청 조기 거절)에만 사용한다.
> 실제 정합성은 반드시 Pessimistic Lock + DB 검증으로 보장한다.

### 5.5 build.gradle 의존성 추가

```groovy
implementation 'com.github.ben-manes.caffeine:caffeine'
```

---

## 6. UseCase 구현 계획

### 6.1 CreateEnrollmentUseCase (핵심)

```
execute(userId, courseId):
  // --- Semaphore 획득 (트랜잭션 외부) ---
  semaphoreManager.tryAcquire(courseId)
  try {
    // --- Caffeine 사전 검증 (DB 미접근) ---
    CourseCapacityInfo cached = courseCapacityCache.getIfPresent(courseId)
    if (cached != null) {
      cached.isOpen()          → 아니면 InvalidCourseStatusException
      cached.isLikelyFull()   → 이면 EnrollmentLimitExceededException (빠른 거절)
    }

    // --- 트랜잭션 시작 (EnrollmentService로 위임) ---
    enrollmentService.createEnrollment(userId, courseId)
      │
      ├─ 1. courseRepository.findByIdWithLock(courseId)  ← PESSIMISTIC_WRITE, timeout=3s
      │     없으면 CourseNotFoundException
      ├─ 2. course.validateOpen()
      ├─ 3. course.validateEnrollmentPeriod()
      ├─ 4. existsByUserIdAndCourseIdAndStatusIn([PENDING, CONFIRMED])
      │     있으면 AlreadyEnrolledException
      ├─ 5. course.increaseCurrentCount()
      │     초과이면 EnrollmentLimitExceededException
      ├─ 6. Enrollment(PENDING) 생성 & 저장
      └─ 7. Cache Evict: courseCapacityCache.invalidate(courseId)
    // --- 트랜잭션 종료 ---

  } finally {
    semaphoreManager.release(courseId)  // 반드시 해제
  }
```

### 6.2 ConfirmPaymentUseCase

```
execute(enrollmentId):
  enrollmentService.confirmPayment(enrollmentId)
    └─ findById → enrollment.confirm() → dirty checking 저장
```

### 6.3 CancelEnrollmentUseCase

```
execute(enrollmentId, userId):
  enrollmentService.cancelEnrollment(enrollmentId, userId)
    └─ findById
    └─ enrollment.cancel(userId)   ← 소유자/상태/7일 검증 포함
    └─ course.decreaseCurrentCount()
    └─ Cache Evict: courseCapacityCache.invalidate(courseId)
```

### 6.4 GetMyEnrollmentsUseCase

```
execute(userId):
  enrollmentRepository.findAllByUserIdWithCourse(userId)  ← JOIN FETCH로 N+1 방지
  → EnrollmentResponse 변환 (강의명, 가격, 상태 포함)
```

### 6.5 CreateCourseUseCase

```
execute(request):
  endDate < startDate → 예외
  Course(DRAFT, currentCount=0) 생성 → save
```

### 6.6 UpdateCourseStatusUseCase

```
execute(courseId, nextStatus):
  findById → course.changeStatus(nextStatus) → dirty checking 저장
  Cache Evict: courseCapacityCache.invalidate(courseId)
```

### 6.7 GetCourseListUseCase

```
execute(status):
  status 있으면 findAllByStatus(status) — idx_course_status 인덱스 활용
  없으면 findAll()
```

### 6.8 GetCourseDetailUseCase

```
execute(courseId):
  findById → CourseDetailResponse (currentCount, capacity 포함)
```

---

## 7. 인덱스 전략

| 테이블 | 인덱스 | 목적 |
|---|---|---|
| `course` | `(status)` | 상태별 강의 목록 조회 (`GetCourseListUseCase`) |
| `enrollment` | `(user_id)` | 내 수강 내역 조회 (`GetMyEnrollmentsUseCase`) |
| `enrollment` | `(course_id)` | 강의별 신청 조회 |
| `enrollment` | `(user_id, course_id, status)` | 중복 신청 검증 (`existsByUserIdAndCourseIdAndStatusIn`) |

> H2는 인-메모리 환경이므로 실제 성능 차이는 작지만,
> `@Table(indexes = {...})` 로 선언하면 실서비스 환경(MySQL 등) 전환 시 DDL 자동 적용된다.

---

## 8. 예외 처리

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    // 도메인 예외 → 400/404
    // EnrollmentQueueFullException → 503 (Service Unavailable)
}
```

| Exception | HTTP | 상황 |
|---|---|---|
| `CourseNotFoundException` | 404 | 강의 없음 |
| `InvalidCourseStatusException` | 400 | OPEN 아님 / 기간 외 |
| `EnrollmentLimitExceededException` | 400 | 정원 초과 |
| `AlreadyEnrolledException` | 400 | 중복 신청 |
| `CancellationPeriodExpiredException` | 400 | 7일 초과 취소 |
| `EnrollmentQueueFullException` | 503 | 동시 요청 한도 초과 |

ErrorResponse 공통 형식:
```json
{ "code": "ENROLLMENT_LIMIT_EXCEEDED", "message": "정원이 초과되었습니다." }
```

---

## 9. API 엔드포인트

| Method | URI | UseCase |
|---|---|---|
| POST | `/courses` | CreateCourseUseCase |
| PATCH | `/courses/{courseId}/status` | UpdateCourseStatusUseCase |
| GET | `/courses` | GetCourseListUseCase |
| GET | `/courses/{courseId}` | GetCourseDetailUseCase |
| POST | `/enrollments` | CreateEnrollmentUseCase |
| PATCH | `/enrollments/{enrollmentId}/confirm` | ConfirmPaymentUseCase |
| PATCH | `/enrollments/{enrollmentId}/cancel` | CancelEnrollmentUseCase |
| GET | `/enrollments/me` | GetMyEnrollmentsUseCase |

---

## 10. 동시성 테스트 계획

**시나리오 1**: capacity=30 강의에 100명 동시 신청 → 정확히 30건만 성공

```java
@SpringBootTest
class CreateEnrollmentConcurrencyTest {

    @Test
    void 동시_100명_신청시_정원_초과하지_않는다() throws InterruptedException {
        int threadCount = 100;
        int capacity = 30;
        // capacity=30, OPEN 상태 강의 생성 (각 userId 다르게)

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            final long userId = i + 1L;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();  // 모든 스레드가 준비된 후 동시에 시작
                    createEnrollmentUseCase.execute(userId, courseId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();  // 동시 시작
        done.await();

        assertThat(successCount.get()).isEqualTo(capacity);
        Course result = courseRepository.findById(courseId).get();
        assertThat(result.getCurrentCount()).isEqualTo(capacity);
    }
}
```

**시나리오 2**: 동일 사용자 중복 신청 → 1건만 성공

---

## 11. application.yaml

```yaml
spring:
  application:
    name: liveklass-assignment
  datasource:
    url: jdbc:h2:mem:liveklass;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    hikari:
      maximum-pool-size: 10       # Connection Pool 크기
      connection-timeout: 3000    # Connection 획득 대기 최대 3초
  h2:
    console:
      enabled: true
      path: /h2-console
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true
    properties:
      hibernate:
        format_sql: true
```

> `EnrollmentSemaphoreManager`의 `MAX_CONCURRENT_PER_COURSE`는
> `hikari.maximum-pool-size` 를 기준으로 설정한다. (예: pool=10이면 강의당 5)

---

## 12. 구현 순서

1. `application.yaml` + `build.gradle` (Caffeine 의존성)
2. `common/exception` — 예외 클래스 5종 + GlobalExceptionHandler
3. `common/concurrency` — EnrollmentSemaphoreManager
4. `member` 도메인 — Entity, Repository (최소)
5. `course` domain — entity, repository interface, domain/service
6. `course` infrastructure — JpaRepository (인덱스 포함)
7. `course` application — service (트랜잭션), usecase (오케스트레이션)
8. `course` presentation — Controller, DTO
9. `enrollment` domain — entity (도메인 메서드 포함), repository interface
10. `enrollment` infrastructure — JpaRepository (Lock, JOIN FETCH, 인덱스)
11. CacheConfig — Caffeine 설정
12. `enrollment` application — service, usecase (CreateEnrollmentUseCase 최우선)
13. `enrollment` presentation — Controller, DTO
14. 동시성 통합 테스트 (시나리오 1, 2)
