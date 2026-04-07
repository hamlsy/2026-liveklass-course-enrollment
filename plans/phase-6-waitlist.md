# Phase 6: 대기열(Waitlist) 기능 구현

## 목표
- 수강 정원 초과 시 대기 등록 기능
- 취소 발생 시 대기열 첫 번째 유저 자동 승격 (PENDING 전환)
- 대기 순번 조회 API
- 대기 취소 API

---

## 1. 패키지 구조 (추가 파일)

```
enrollment/
├── domain/
│   ├── entity/
│   │   ├── Waitlist.java              ← NEW
│   │   └── WaitlistStatus.java        ← NEW (enum)
│   └── repository/
│       └── WaitlistRepository.java    ← NEW
├── infrastructure/
│   └── WaitlistJpaRepository.java     ← NEW
├── application/
│   ├── service/
│   │   └── WaitlistService.java       ← NEW
│   └── usecase/
│       ├── JoinWaitlistUseCase.java        ← NEW
│       ├── CancelWaitlistUseCase.java      ← NEW
│       └── GetWaitlistPositionUseCase.java ← NEW
└── presentation/
    ├── WaitlistController.java        ← NEW
    └── response/
        └── WaitlistResponse.java      ← NEW

common/
└── exception/
    ├── AlreadyInWaitlistException.java    ← NEW
    └── WaitlistNotFoundException.java    ← NEW
```

---

## 2. 도메인 엔티티 설계

### 2.1 WaitlistStatus.java (Enum)

```java
package com.hamlsy.liveklass_assignment.enrollment.domain.entity;

public enum WaitlistStatus {
    WAITING,    // 대기 중
    PROMOTED,   // 수강 신청으로 자동 승격됨
    CANCELLED   // 대기 취소됨
}
```

### 2.2 Waitlist.java

```java
package com.hamlsy.liveklass_assignment.enrollment.domain.entity;

import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "waitlist",
    indexes = {
        @Index(name = "idx_waitlist_course_status", columnList = "course_id, status"),
        @Index(name = "idx_waitlist_user_course",   columnList = "user_id, course_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Waitlist {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaitlistStatus status;

    @Column(nullable = false)
    private LocalDateTime waitlistedAt;

    @Builder
    public Waitlist(Long userId, Course course) {
        this.userId       = userId;
        this.course       = course;
        this.status       = WaitlistStatus.WAITING;
        this.waitlistedAt = LocalDateTime.now();
    }

    // ── 도메인 메서드 ─────────────────────────────────────────────

    /**
     * 대기열에서 수강 신청으로 승격.
     * 상태를 PROMOTED로 변경 — Enrollment 생성은 서비스 레이어에서 처리.
     */
    public void promote() {
        this.status = WaitlistStatus.PROMOTED;
    }

    /**
     * 대기 취소.
     * 이미 PROMOTED되었거나 CANCELLED면 no-op (멱등성 보장).
     */
    public boolean cancel() {
        if (this.status != WaitlistStatus.WAITING) {
            return false;  // 이미 처리된 상태
        }
        this.status = WaitlistStatus.CANCELLED;
        return true;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
```

**설계 근거:**
- `waitlistedAt` 기준 오름차순 정렬로 FIFO(선착순) 대기열 보장
- `promote()` / `cancel()` 도메인 메서드로 상태 전이를 엔티티 내부에 캡슐화
- 인덱스 `(course_id, status)` → 승격 대상 조회 쿼리 최적화

---

## 3. Repository 설계

### 3.1 WaitlistRepository.java (Interface)

```java
package com.hamlsy.liveklass_assignment.enrollment.domain.repository;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Waitlist;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.WaitlistStatus;

import java.util.List;
import java.util.Optional;

public interface WaitlistRepository {

    Waitlist save(Waitlist waitlist);

    Optional<Waitlist> findById(Long id);

    /** 중복 대기 검증 */
    boolean existsByUserIdAndCourseIdAndStatus(Long userId, Long courseId, WaitlistStatus status);

    /**
     * 승격 대상 조회 — WAITING 상태 중 가장 오래된 1건.
     * waitlistedAt ASC 정렬 → FIFO 보장.
     * idx_waitlist_course_status 인덱스 활용.
     */
    Optional<Waitlist> findFirstByCourseIdAndStatusOrderByWaitlistedAtAsc(
        Long courseId, WaitlistStatus status
    );

    /**
     * 대기 순번 계산.
     * userId 앞에 WAITING 상태인 항목 수 + 1 = 현재 순번.
     */
    long countByCourseIdAndStatusAndWaitlistedAtBefore(
        Long courseId, WaitlistStatus status, java.time.LocalDateTime waitlistedAt
    );

    /** 유저 대기 항목 조회 (본인 확인용) */
    Optional<Waitlist> findByUserIdAndCourseIdAndStatus(
        Long userId, Long courseId, WaitlistStatus status
    );
}
```

### 3.2 WaitlistJpaRepository.java (Infrastructure)

```java
package com.hamlsy.liveklass_assignment.enrollment.infrastructure;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Waitlist;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.WaitlistStatus;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.WaitlistRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WaitlistJpaRepository extends JpaRepository<Waitlist, Long>, WaitlistRepository {

    boolean existsByUserIdAndCourseIdAndStatus(Long userId, Long courseId, WaitlistStatus status);

    Optional<Waitlist> findFirstByCourseIdAndStatusOrderByWaitlistedAtAsc(
        Long courseId, WaitlistStatus status
    );

    long countByCourseIdAndStatusAndWaitlistedAtBefore(
        Long courseId, WaitlistStatus status, LocalDateTime waitlistedAt
    );

    Optional<Waitlist> findByUserIdAndCourseIdAndStatus(
        Long userId, Long courseId, WaitlistStatus status
    );
}
```

---

## 4. 예외 클래스

### AlreadyInWaitlistException.java

```java
package com.hamlsy.liveklass_assignment.common.exception;

public class AlreadyInWaitlistException extends BusinessException {
    public AlreadyInWaitlistException() {
        super(ErrorCode.ALREADY_IN_WAITLIST);
    }
}
```

### WaitlistNotFoundException.java

```java
package com.hamlsy.liveklass_assignment.common.exception;

public class WaitlistNotFoundException extends BusinessException {
    public WaitlistNotFoundException() {
        super(ErrorCode.WAITLIST_NOT_FOUND);
    }
}
```

### ErrorCode 추가 항목

```java
// ErrorCode.java 에 추가
ALREADY_IN_WAITLIST("ALREADY_IN_WAITLIST", "이미 대기열에 등록되어 있습니다."),
WAITLIST_NOT_FOUND("WAITLIST_NOT_FOUND", "대기열 항목을 찾을 수 없습니다."),
WAITLIST_UNAUTHORIZED("WAITLIST_UNAUTHORIZED", "본인의 대기열만 취소할 수 있습니다."),
```

---

## 5. WaitlistService (Application Service)

```java
package com.hamlsy.liveklass_assignment.enrollment.application.service;

import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.*;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WaitlistService {

    private final CourseRepository courseRepository;
    private final WaitlistRepository waitlistRepository;
    private final EnrollmentRepository enrollmentRepository;

    /**
     * 대기열 등록.
     *
     * 호출 조건: 정원이 꽉 찬 OPEN 강의.
     * 중복 대기 검증 후 Waitlist(WAITING) 생성.
     *
     * ※ Caffeine 사전 필터링은 JoinWaitlistUseCase에서 처리
     *   (isNotOpen / isOutOfPeriod 캐시 hit 시 DB 미접근 즉시 거절).
     *   정합성 보장은 이 메서드의 DB 검증이 담당.
     */
    @Transactional
    public Waitlist joinWaitlist(Long userId, Long courseId) {
        Course course = courseRepository.findById(courseId)
            .orElseThrow(CourseNotFoundException::new);

        course.validateOpen();
        course.validateEnrollmentPeriod();

        // 정원 초과 여부 검증: 아직 자리가 남아있으면 수강 신청으로 유도
        // 대기열은 정원이 꽉 찬 강의에만 등록 가능
        if (!course.isFull()) {
            throw new CourseNotFullException();
        }

        // 중복 대기 검증
        if (waitlistRepository.existsByUserIdAndCourseIdAndStatus(
                userId, courseId, WaitlistStatus.WAITING)) {
            throw new AlreadyInWaitlistException();
        }

        // 이미 수강 중(PENDING/CONFIRMED)인 경우 대기 불가
        if (enrollmentRepository.existsByUserIdAndCourseIdAndStatusIn(
                userId, courseId,
                java.util.List.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED))) {
            throw new AlreadyEnrolledException();
        }

        Waitlist waitlist = Waitlist.builder()
            .userId(userId)
            .course(course)
            .build();

        return waitlistRepository.save(waitlist);
    }

    /**
     * 대기열 취소.
     *
     * WAITING → CANCELLED 전환.
     * PROMOTED: 이미 Enrollment가 생성된 상태이므로 취소 불가 — 명시적 예외.
     * CANCELLED: 멱등 처리 (no-op, 현재 상태 반환).
     */
    @Transactional
    public Waitlist cancelWaitlist(Long waitlistId, Long userId) {
        Waitlist waitlist = waitlistRepository.findById(waitlistId)
            .orElseThrow(WaitlistNotFoundException::new);

        if (!waitlist.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.WAITLIST_UNAUTHORIZED);
        }

        // PROMOTED 상태: Enrollment가 이미 생성되었으므로 대기열 취소 불가
        // (Enrollment 취소는 별도 /enrollments/{id}/cancel API 사용)
        if (waitlist.getStatus() == WaitlistStatus.PROMOTED) {
            throw new BusinessException(ErrorCode.WAITLIST_ALREADY_PROMOTED);
        }

        // WAITING → CANCELLED (CANCELLED이면 멱등 no-op)
        waitlist.cancel();
        return waitlist;
    }

    /**
     * 대기 순번 조회.
     *
     * countByCourseIdAndStatusAndWaitlistedAtBefore + 1 = 현재 순번.
     * 예) 앞에 2명 WAITING → 내 순번 = 3
     *
     * [DB 부하 주의]
     * 순번 조회마다 COUNT 쿼리가 실행된다.
     * GetWaitlistPositionUseCase에서 waitlistPositionCache (TTL 2초)로 결과를 캐싱해
     * 동시 폴링 요청의 DB 타격을 차단한다.
     * 2초 stale은 사용자 경험에 무해하며, 실제 정합성은 승격 시점에 보장된다.
     */
    @Transactional(readOnly = true)
    public long getWaitlistPosition(Long userId, Long courseId) {
        Waitlist waitlist = waitlistRepository
            .findByUserIdAndCourseIdAndStatus(userId, courseId, WaitlistStatus.WAITING)
            .orElseThrow(WaitlistNotFoundException::new);

        return waitlistRepository.countByCourseIdAndStatusAndWaitlistedAtBefore(
            courseId, WaitlistStatus.WAITING, waitlist.getWaitlistedAt()
        ) + 1;
    }

    /**
     * 취소 발생 시 대기열 첫 번째 유저 자동 승격.
     *
     * 반환값: 승격 대상이 있으면 true, 없으면 false.
     * caller(EnrollmentService)는 false일 때만 decreaseCurrentCount() 호출.
     *
     * 호출 위치: EnrollmentService.cancelEnrollment() 내부 (Pessimistic Lock 보유 중).
     * Propagation.MANDATORY: 반드시 기존 트랜잭션 내에서 실행되어야 함.
     *   - 독립 트랜잭션으로 분리하면 Lock 해제 후 두 취소가 동일 Waitlist 항목을 중복 승격할 수 있음.
     *   - Lock 범위 내 실행이 정합성의 핵심.
     *
     * 승격 흐름:
     *   1. WAITING 중 waitlistedAt ASC 첫 번째 조회 (FIFO)
     *   2. Waitlist.promote() → PROMOTED
     *   3. Enrollment(PENDING) 신규 생성
     *   4. course.increaseCurrentCount() (Lock 이미 보유)
     *   → currentCount: cancel -1 후 promote +1 = 순증감 없음
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryPromoteNextWaiting(Long courseId, Course lockedCourse) {
        Optional<Waitlist> candidate = waitlistRepository
            .findFirstByCourseIdAndStatusOrderByWaitlistedAtAsc(courseId, WaitlistStatus.WAITING);

        if (candidate.isEmpty()) {
            return false;
        }

        Waitlist waitlist = candidate.get();
        waitlist.promote();

        Enrollment promoted = Enrollment.builder()
            .userId(waitlist.getUserId())
            .course(lockedCourse)
            .build();
        enrollmentRepository.save(promoted);

        lockedCourse.increaseCurrentCount();
        return true;
    }
}
```

---

## 6. EnrollmentService 수정 — cancelEnrollment

취소 완료 후 대기열 승격을 연동한다.

```java
// EnrollmentService — WaitlistService 필드 추가 (기존 필드 아래에 추가)
// @RequiredArgsConstructor가 생성자 주입을 처리하므로 선언만 추가
private final WaitlistService waitlistService;

// EnrollmentService.cancelEnrollment() 수정
@Transactional
public Enrollment cancelEnrollment(Long enrollmentId, Long userId) {
    Enrollment enrollment = enrollmentRepository.findByIdWithCourse(enrollmentId)
        .orElseThrow(EnrollmentNotFoundException::new);

    boolean actuallyCancelled = enrollment.cancel(userId);

    if (actuallyCancelled) {
        Long courseId = enrollment.getCourse().getId();

        // Pessimistic Lock 획득 (findByIdWithCourse는 Lock 없이 로딩했으므로 별도 Lock 필요)
        Course lockedCourse = courseRepository.findByIdWithLock(courseId)
            .orElseThrow(CourseNotFoundException::new);

        // ── 대기열 승격 시도 (Lock 보유 중 동일 트랜잭션 내 실행) ──
        // 승격 대상 있음: promote() + Enrollment 생성 + increaseCurrentCount()
        //   → currentCount -1(취소) +1(승격) = 순증감 없음, decreaseCurrentCount 불필요
        // 승격 대상 없음: decreaseCurrentCount()로 정원 반환
        boolean promoted = waitlistService.tryPromoteNextWaiting(courseId, lockedCourse);
        if (!promoted) {
            lockedCourse.decreaseCurrentCount();
        }

        courseCapacityCache.invalidate(courseId);
    }

    return enrollment;
}

---

## 7. UseCase

### JoinWaitlistUseCase.java

```java
/**
 * 대기열 등록 UseCase.
 *
 * [방어 1] Caffeine 사전 검증 (DB 미접근)
 *   - 캐시 hit 시 OPEN 아님 / 기간 외 조건을 즉시 거절.
 *   - isLikelyFull() 체크는 제외: 대기열은 정원 초과 상황에서 등록하므로
 *     캐시가 full이어도 실제로는 자리가 생겼을 수 있음 (invalidate 지연 가능성).
 *     정원 초과 여부의 정합성 검증은 WaitlistService에서 DB 기준으로 처리.
 *
 * [방어 2] Semaphore 스로틀링 (DB Connection Pool 보호)
 *   - 인기 강의 마감 직후 대기 등록 요청이 몰릴 때 무제한 DB 진입 방지.
 *   - createEnrollment와 동일한 per-course Semaphore 공유.
 *   - 트랜잭션 외부에서 acquire → 내부 작업 완료 후 release.
 */
@Component
@RequiredArgsConstructor
public class JoinWaitlistUseCase {

    private final WaitlistService waitlistService;
    private final EnrollmentSemaphoreManager semaphoreManager;
    private final Cache<Long, CourseCapacityInfo> courseCapacityCache;

    public WaitlistResponse execute(Long userId, Long courseId) {
        // ── [방어 1] Caffeine 사전 검증 ──────────────────────────────
        CourseCapacityInfo cached = courseCapacityCache.getIfPresent(courseId);
        if (cached != null) {
            if (cached.isNotOpen()) {
                throw new InvalidCourseStatusException();
            }
            if (cached.isOutOfPeriod()) {
                throw new InvalidCourseStatusException(ErrorCode.INVALID_ENROLLMENT_PERIOD);
            }
        }

        // ── [방어 2] Semaphore 획득 (트랜잭션 외부) ──────────────────
        semaphoreManager.acquire(courseId);
        try {
            Waitlist waitlist = waitlistService.joinWaitlist(userId, courseId);
            return WaitlistResponse.from(waitlist);
        } finally {
            semaphoreManager.release(courseId);
        }
    }
}
```

### CancelWaitlistUseCase.java

```java
@Component
@RequiredArgsConstructor
public class CancelWaitlistUseCase {

    private final WaitlistService waitlistService;

    public WaitlistResponse execute(Long waitlistId, Long userId) {
        return WaitlistResponse.from(waitlistService.cancelWaitlist(waitlistId, userId));
    }
}
```

### GetWaitlistPositionUseCase.java

```java
/**
 * 순번 조회마다 COUNT 쿼리가 발생하므로 TTL 2초 캐시로 DB 타격을 차단.
 * 캐시 키: "courseId:userId" 문자열 조합.
 * 2초 stale은 사용자 경험에 무해하다 (순번이 1~2초 지연 표시되어도 무방).
 */
@Component
@RequiredArgsConstructor
public class GetWaitlistPositionUseCase {

    private final WaitlistService waitlistService;
    private final Cache<String, Long> waitlistPositionCache;

    public WaitlistPositionResponse execute(Long userId, Long courseId) {
        String cacheKey = courseId + ":" + userId;
        Long cached = waitlistPositionCache.getIfPresent(cacheKey);
        if (cached != null) {
            return new WaitlistPositionResponse(courseId, userId, cached);
        }

        long position = waitlistService.getWaitlistPosition(userId, courseId);
        waitlistPositionCache.put(cacheKey, position);
        return new WaitlistPositionResponse(courseId, userId, position);
    }
}
```

---

## 8. Response DTO

### WaitlistResponse.java

```java
package com.hamlsy.liveklass_assignment.enrollment.presentation.response;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Waitlist;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.WaitlistStatus;
import java.time.LocalDateTime;

public record WaitlistResponse(
    Long waitlistId,
    Long userId,
    Long courseId,
    String courseTitle,
    WaitlistStatus status,
    LocalDateTime waitlistedAt
) {
    public static WaitlistResponse from(Waitlist waitlist) {
        return new WaitlistResponse(
            waitlist.getId(),
            waitlist.getUserId(),
            waitlist.getCourse().getId(),
            waitlist.getCourse().getTitle(),
            waitlist.getStatus(),
            waitlist.getWaitlistedAt()
        );
    }
}
```

### WaitlistPositionResponse.java

```java
public record WaitlistPositionResponse(
    Long courseId,
    Long userId,
    long position   // 1-based. 1 = 다음 승격 대상
) {}
```

---

## 9. WaitlistController

```java
@RestController
@RequestMapping("/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final JoinWaitlistUseCase joinWaitlistUseCase;
    private final CancelWaitlistUseCase cancelWaitlistUseCase;
    private final GetWaitlistPositionUseCase getWaitlistPositionUseCase;

    /** 대기열 등록 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WaitlistResponse joinWaitlist(
        @RequestParam Long userId,
        @RequestParam Long courseId
    ) {
        return joinWaitlistUseCase.execute(userId, courseId);
    }

    /** 대기 취소 */
    @PatchMapping("/{waitlistId}/cancel")
    public WaitlistResponse cancelWaitlist(
        @PathVariable Long waitlistId,
        @RequestParam Long userId
    ) {
        return cancelWaitlistUseCase.execute(waitlistId, userId);
    }

    /** 대기 순번 조회 */
    @GetMapping("/position")
    public WaitlistPositionResponse getPosition(
        @RequestParam Long userId,
        @RequestParam Long courseId
    ) {
        return getWaitlistPositionUseCase.execute(userId, courseId);
    }
}
```

---

## 10. CacheConfig 추가 — waitlistPositionCache

`GetWaitlistPositionUseCase`가 사용하는 순번 캐시 Bean을 `CacheConfig.java`에 추가한다.

```java
// CacheConfig.java 에 추가

/**
 * 대기 순번 캐시 (per user-course)
 * TTL 2초: COUNT 쿼리 중복 실행 방지.
 * 키: "courseId:userId" 문자열
 */
@Bean
public Cache<String, Long> waitlistPositionCache() {
    return Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(2, TimeUnit.SECONDS)
        .build();
}
```

---

## 11. Course 도메인 메서드 추가 — `isFull()`

`WaitlistService.joinWaitlist()`에서 호출하는 `isFull()` 메서드를 `Course.java`에 추가한다.

```java
// Course.java 에 추가

/** 현재 정원이 꽉 찼는지 여부 */
public boolean isFull() {
    return this.currentCount >= this.capacity;
}
```

`CourseNotFullException`도 새로 추가:

```java
// ErrorCode 추가
COURSE_NOT_FULL("COURSE_NOT_FULL", "강의 정원이 남아있습니다. 수강 신청을 이용하세요."),
```

---

## 12. API 엔드포인트 요약

| Method | URI | 설명 |
|---|---|---|
| POST | `/waitlist?userId={id}&courseId={id}` | 대기열 등록 |
| PATCH | `/waitlist/{waitlistId}/cancel?userId={id}` | 대기 취소 |
| GET | `/waitlist/position?userId={id}&courseId={id}` | 대기 순번 조회 |

---

## 13. GlobalExceptionHandler 추가 항목

```java
// GlobalExceptionHandler에 추가
```

| Exception | HTTP | 상황 |
|---|---|---|
| `AlreadyInWaitlistException` | 400 | 이미 대기열에 존재 |
| `CourseNotFullException` | 400 | 정원이 남아있어 대기열 등록 불가 |
| `WaitlistNotFoundException` | 404 | 대기열 항목 없음 |
| `BusinessException(WAITLIST_ALREADY_PROMOTED)` | 409 | 이미 수강 신청으로 승격된 상태 |

---

## 14. 동시성 고려사항

대기열 등록도 동시에 몰릴 수 있다. 그러나 `joinWaitlist`는 `currentCount`를 수정하지 않으므로 Pessimistic Lock 불필요.
단, 중복 대기 검증의 정합성을 위해 `existsByUserIdAndCourseIdAndStatus` 실행 후 저장 사이의 Race Condition은
DB unique constraint로 보완 가능:

```java
// Waitlist 엔티티에 추가 (선택)
@Table(
    ...,
    uniqueConstraints = @UniqueConstraint(
        name = "uq_waitlist_user_course_waiting",
        columnNames = {"user_id", "course_id", "status"}  // status=WAITING 중복 방지는 앱 레벨에서
    )
)
```

> WAITING 상태의 유니크 제약은 status 컬럼이 포함되어 PROMOTED/CANCELLED는 중복 허용하므로
> 앱 레벨 검증(`existsByUserIdAndCourseIdAndStatus`)과 병행한다.

---

## 15. 구현 순서

1. `Course.java` — `isFull()` 도메인 메서드 추가
2. `WaitlistStatus.java` (enum)
3. `Waitlist.java` (도메인 엔티티)
4. `WaitlistRepository.java` (인터페이스)
5. `WaitlistJpaRepository.java` (JPA 구현체)
6. 예외 클래스 + `ErrorCode` 추가 (AlreadyInWaitlist / WaitlistNotFound / CourseNotFull / WaitlistAlreadyPromoted)
7. `CacheConfig.java` — `waitlistPositionCache` Bean 추가
8. `WaitlistService.java` (tryPromoteNextWaiting boolean 반환, Propagation.MANDATORY)
9. `EnrollmentService.cancelEnrollment()` 수정 (WaitlistService 필드 주입 + 승격 연동)
10. `JoinWaitlistUseCase` (Semaphore + Caffeine 방어)
11. `CancelWaitlistUseCase`, `GetWaitlistPositionUseCase` (position 캐시 적용)
12. `WaitlistResponse`, `WaitlistPositionResponse`
13. `WaitlistController`
14. `GlobalExceptionHandler` 항목 추가
15. 테스트: 대기열 등록 → 취소 → 자동 승격 시나리오

## 16. 완료 체크리스트

- [ ] Course.java `isFull()` 추가
- [ ] WaitlistStatus enum
- [ ] Waitlist 엔티티 (도메인 메서드 포함)
- [ ] WaitlistRepository 인터페이스
- [ ] WaitlistJpaRepository 구현체
- [ ] 예외 클래스 4종 + ErrorCode 항목 (AlreadyInWaitlist / WaitlistNotFound / CourseNotFull / WAITLIST_ALREADY_PROMOTED)
- [ ] CacheConfig — waitlistPositionCache Bean
- [ ] WaitlistService (joinWaitlist / cancelWaitlist / getWaitlistPosition / tryPromoteNextWaiting boolean + MANDATORY)
- [ ] EnrollmentService — WaitlistService 필드 주입 + cancelEnrollment 승격 연동
- [ ] JoinWaitlistUseCase — Semaphore + Caffeine 사전 검증
- [ ] CancelWaitlistUseCase, GetWaitlistPositionUseCase — position 캐시
- [ ] DTO 2종 (WaitlistResponse, WaitlistPositionResponse)
- [ ] WaitlistController
- [ ] GlobalExceptionHandler 추가
