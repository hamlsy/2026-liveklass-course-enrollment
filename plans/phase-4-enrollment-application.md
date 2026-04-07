# Phase 4: Enrollment Application 레이어 구현

## 목표
- EnrollmentService (트랜잭션 경계, 멱등성 처리)
- 4개 UseCase 구현 (CreateEnrollmentUseCase 핵심)
- 멱등성 키 캐시 저장 (CreateEnrollmentUseCase)
- EnrollmentController + DTO

---

## 1. 패키지 구조

```
enrollment/
├── application/
│   ├── service/
│   │   └── EnrollmentService.java
│   └── usecase/
│       ├── CreateEnrollmentUseCase.java
│       ├── ConfirmPaymentUseCase.java
│       ├── CancelEnrollmentUseCase.java
│       └── GetMyEnrollmentsUseCase.java
└── presentation/
    ├── EnrollmentController.java
    ├── request/
    │   └── CreateEnrollmentRequest.java
    └── response/
        └── EnrollmentResponse.java

common/
└── config/
    └── CacheConfig.java      ← Phase 1에서 작성
```

---

## 2. EnrollmentService (Application Service)

```java
package com.hamlsy.liveklass_assignment.enrollment.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.hamlsy.liveklass_assignment.common.concurrency.CourseCapacityInfo;
import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import com.hamlsy.liveklass_assignment.enrollment.domain.service.EnrollmentDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final EnrollmentDomainService enrollmentDomainService;
    private final Cache<Long, CourseCapacityInfo> courseCapacityCache;

    /**
     * 수강 신청 (핵심 트랜잭션)
     *
     * Pessimistic Lock 범위:
     *   findByIdWithLock 호출 시점 ~ 트랜잭션 커밋까지 Row Lock 유지.
     *   동시에 같은 courseId로 진입한 스레드들이 순차 처리됨.
     *
     * 처리 순서:
     *   1. PESSIMISTIC_WRITE Lock으로 Course 조회
     *   2. 상태(OPEN) 검증
     *   3. 신청 기간 검증
     *   4. 중복 신청 검증 (DomainService)
     *   5. currentCount 증가 (원자적)
     *   6. Enrollment(PENDING) 저장
     *   7. 캐시 무효화
     */
    @Transactional
    public Enrollment createEnrollment(Long userId, Long courseId) {
        Course course = courseRepository.findByIdWithLock(courseId)
            .orElseThrow(CourseNotFoundException::new);

        course.validateOpen();
        course.validateEnrollmentPeriod();
        enrollmentDomainService.validateNoDuplicateEnrollment(userId, courseId);
        course.increaseCurrentCount();

        Enrollment enrollment = Enrollment.builder()
            .userId(userId)
            .course(course)
            .build();
        Enrollment saved = enrollmentRepository.save(enrollment);

        // currentCount 변경 → 사전 필터 캐시 무효화
        courseCapacityCache.invalidate(courseId);

        return saved;
    }

    /**
     * 결제 확정: PENDING → CONFIRMED
     *
     * [멱등성] confirm() 내부에서 이미 CONFIRMED면 아무 동작 없이 반환.
     * 클라이언트 재시도 시 동일 결과를 보장.
     */
    @Transactional
    public Enrollment confirmPayment(Long enrollmentId) {
        Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
            .orElseThrow(EnrollmentNotFoundException::new);
        enrollment.confirm();  // 멱등: 이미 CONFIRMED면 no-op
        return enrollment;     // dirty checking으로 저장
    }

    /**
     * 수강 취소: CONFIRMED → CANCELLED + currentCount 감소
     *
     * [Race Condition 방지]
     * decreaseCurrentCount도 currentCount를 수정하므로 Pessimistic Lock 필요.
     * findByIdWithCourse로 enrollment + course를 단일 쿼리로 로딩한 뒤,
     * course에 별도로 Pessimistic Lock을 획득하여 currentCount 감소를 원자적으로 처리.
     *
     * [멱등성]
     * cancel() 반환값(boolean)으로 실제 취소 여부 판단.
     * false(이미 취소됨)면 currentCount 재감소 없이 현재 상태 반환.
     *
     * [N+1 방지]
     * findByIdWithCourse — enrollment + course를 JOIN FETCH로 단일 쿼리 로딩.
     */
    @Transactional
    public Enrollment cancelEnrollment(Long enrollmentId, Long userId) {
        // enrollment + course 단일 쿼리 로딩 (N+1 방지)
        Enrollment enrollment = enrollmentRepository.findByIdWithCourse(enrollmentId)
            .orElseThrow(EnrollmentNotFoundException::new);

        // 도메인 메서드: 소유자 검증 + 상태 검증 + 7일 검증 + CANCELLED 변경
        // 반환값: true = 실제 취소됨, false = 이미 취소된 상태 (멱등)
        boolean actuallyCancelled = enrollment.cancel(userId);

        if (actuallyCancelled) {
            Long courseId = enrollment.getCourse().getId();

            // course에 Pessimistic Lock 획득 후 currentCount 감소
            // → 동시 취소 시 Race Condition 방지
            Course lockedCourse = courseRepository.findByIdWithLock(courseId)
                .orElseThrow(CourseNotFoundException::new);
            lockedCourse.decreaseCurrentCount();

            // 캐시 무효화
            courseCapacityCache.invalidate(courseId);
        }

        return enrollment;
    }

    /**
     * 내 수강 내역 조회
     * JOIN FETCH로 N+1 방지
     */
    @Transactional(readOnly = true)
    public List<Enrollment> getMyEnrollments(Long userId) {
        return enrollmentRepository.findAllByUserIdWithCourse(userId);
    }
}
```

---

## 3. CreateEnrollmentUseCase (핵심 — 3단계 방어 + 멱등성)

```java
package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.github.benmanes.caffeine.cache.Cache;
import com.hamlsy.liveklass_assignment.common.concurrency.CourseCapacityInfo;
import com.hamlsy.liveklass_assignment.common.concurrency.EnrollmentSemaphoreManager;
import com.hamlsy.liveklass_assignment.common.exception.EnrollmentLimitExceededException;
import com.hamlsy.liveklass_assignment.common.exception.InvalidCourseStatusException;
import com.hamlsy.liveklass_assignment.common.exception.ErrorCode;
import com.hamlsy.liveklass_assignment.common.idempotency.IdempotencyInterceptor;
import com.hamlsy.liveklass_assignment.common.idempotency.IdempotencyResponse;
import com.hamlsy.liveklass_assignment.enrollment.application.service.EnrollmentService;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.presentation.request.CreateEnrollmentRequest;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@RequiredArgsConstructor
public class CreateEnrollmentUseCase {

    private final EnrollmentService enrollmentService;
    private final EnrollmentSemaphoreManager semaphoreManager;
    private final Cache<Long, CourseCapacityInfo> courseCapacityCache;
    private final Cache<String, IdempotencyResponse> idempotencyCache;

    /**
     * 수강 신청 실행
     *
     * [방어 1] Caffeine Cache 사전 검증 (트랜잭션 외부, DB 미접근)
     *   - 캐시 hit 시: OPEN 아님 / 기간 외 / 정원 확실히 찬 경우 즉시 거절
     *   - 캐시 miss 시: 생략 → DB에서 정확하게 검증
     *   - 사전 필터 목적. 실제 정합성은 Lock이 보장.
     *
     * [방어 2] 강의별 Semaphore (트랜잭션 외부)
     *   - 강의당 동시 진입 스레드 MAX_CONCURRENT_PER_COURSE(=5)로 제한
     *   - 2초 내 미획득 시 503 반환
     *   - DB Connection Pool 고갈 방지
     *   - Semaphore는 반드시 트랜잭션 시작 전에 획득해야 함
     *     (트랜잭션 내부에 두면 Connection 점유 후 대기 → Pool 고갈 동일)
     *
     * [방어 3] Pessimistic Lock (트랜잭션 내부, EnrollmentService)
     *   - 실제 정합성 보장
     *   - lock.timeout = 3000ms
     *
     * [멱등성] Idempotency-Key 헤더 기반
     *   - 인터셉터(IdempotencyInterceptor)가 preHandle에서 캐시 hit 시 컨트롤러 진입 자체를 차단
     *   - 캐시 miss(첫 요청)이면 UseCase 실행 후 결과를 idempotencyCache에 저장
     */
    public EnrollmentResponse execute(Long userId, CreateEnrollmentRequest request) {
        Long courseId = request.courseId();

        // ── [방어 1] Caffeine 사전 검증 ──────────────────────────────
        CourseCapacityInfo cached = courseCapacityCache.getIfPresent(courseId);
        if (cached != null) {
            if (cached.isNotOpen()) {
                throw new InvalidCourseStatusException();
            }
            if (cached.isOutOfPeriod()) {
                throw new InvalidCourseStatusException(ErrorCode.INVALID_ENROLLMENT_PERIOD);
            }
            if (cached.isLikelyFull()) {
                throw new EnrollmentLimitExceededException();
            }
        }

        // ── [방어 2] Semaphore 획득 (트랜잭션 외부) ──────────────────
        semaphoreManager.acquire(courseId);
        try {
            // ── [방어 3] 실제 트랜잭션 (Pessimistic Lock) ────────────
            Enrollment enrollment = enrollmentService.createEnrollment(userId, courseId);
            EnrollmentResponse response = EnrollmentResponse.from(enrollment);

            // ── [멱등성] 결과를 Idempotency Key와 함께 캐시에 저장 ───
            saveIdempotencyResult(enrollment.getId(), response);

            return response;

        } finally {
            semaphoreManager.release(courseId);  // 반드시 해제
        }
    }

    /**
     * 현재 요청의 Idempotency-Key가 있으면 결과를 캐시에 저장.
     * RequestContextHolder로 현재 요청의 attribute에서 키를 꺼낸다.
     */
    private void saveIdempotencyResult(Long enrollmentId, EnrollmentResponse response) {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;

            HttpServletRequest request = attrs.getRequest();
            String key = (String) request.getAttribute(IdempotencyInterceptor.IDEMPOTENCY_KEY_ATTR);
            if (key != null && !key.isBlank()) {
                idempotencyCache.put(key, new IdempotencyResponse(enrollmentId, response));
            }
        } catch (Exception e) {
            // 캐시 저장 실패는 치명적이지 않음 — 다음 재시도에서 중복 처리됨
            // 중복 신청 검증(DB)이 두 번째 방어선 역할을 함
        }
    }
}
```

> **왜 Semaphore를 트랜잭션 외부에 두는가**
>
> `@Transactional` 메서드 내에 `semaphoreManager.acquire()`를 두면:
> 1. Spring이 트랜잭션 시작 → HikariCP에서 Connection 획득
> 2. Semaphore 대기 → Connection을 점유한 채로 블로킹
> 3. 다른 스레드도 동일하게 Connection 점유 후 대기 → Pool 고갈
>
> 현재 구조는 Semaphore 획득 성공 후 트랜잭션 시작이므로
> 대기 스레드가 Connection을 점유하지 않아 Pool 고갈이 차단된다.

---

## 4. ConfirmPaymentUseCase

```java
package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.hamlsy.liveklass_assignment.enrollment.application.service.EnrollmentService;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConfirmPaymentUseCase {

    private final EnrollmentService enrollmentService;

    /**
     * 결제 확정
     * [멱등성] 이미 CONFIRMED 상태이면 예외 없이 현재 상태 반환.
     * 클라이언트가 응답을 받지 못해 재시도해도 안전하다.
     */
    public EnrollmentResponse execute(Long enrollmentId) {
        return EnrollmentResponse.from(enrollmentService.confirmPayment(enrollmentId));
    }
}
```

---

## 5. CancelEnrollmentUseCase

```java
package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.hamlsy.liveklass_assignment.enrollment.application.service.EnrollmentService;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CancelEnrollmentUseCase {

    private final EnrollmentService enrollmentService;

    /**
     * 수강 취소
     * [멱등성] 이미 CANCELLED 상태이면 예외 없이 현재 상태 반환.
     * currentCount 재감소 없음 (EnrollmentService에서 boolean 반환값으로 제어).
     *
     * @param userId 요청자 ID — 실제 환경에서는 SecurityContext에서 추출
     */
    public EnrollmentResponse execute(Long enrollmentId, Long userId) {
        return EnrollmentResponse.from(enrollmentService.cancelEnrollment(enrollmentId, userId));
    }
}
```

---

## 6. GetMyEnrollmentsUseCase

```java
package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.hamlsy.liveklass_assignment.enrollment.application.service.EnrollmentService;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class GetMyEnrollmentsUseCase {

    private final EnrollmentService enrollmentService;

    public List<EnrollmentResponse> execute(Long userId) {
        return enrollmentService.getMyEnrollments(userId).stream()
            .map(EnrollmentResponse::from)
            .toList();
    }
}
```

---

## 7. Request / Response DTO

### CreateEnrollmentRequest.java

```java
package com.hamlsy.liveklass_assignment.enrollment.presentation.request;

public record CreateEnrollmentRequest(Long courseId) {}
```

### EnrollmentResponse.java

```java
package com.hamlsy.liveklass_assignment.enrollment.presentation.response;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import java.time.LocalDateTime;

public record EnrollmentResponse(
    Long enrollmentId,
    Long userId,
    Long courseId,
    String courseTitle,
    int price,
    EnrollmentStatus status,
    LocalDateTime enrolledAt,
    LocalDateTime confirmedAt
) {
    public static EnrollmentResponse from(Enrollment enrollment) {
        return new EnrollmentResponse(
            enrollment.getId(),
            enrollment.getUserId(),
            enrollment.getCourse().getId(),
            enrollment.getCourse().getTitle(),  // JOIN FETCH 로딩됨 — 추가 쿼리 없음
            enrollment.getCourse().getPrice(),
            enrollment.getStatus(),
            enrollment.getEnrolledAt(),
            enrollment.getConfirmedAt()
        );
    }
}
```

---

## 8. EnrollmentController

```java
package com.hamlsy.liveklass_assignment.enrollment.presentation;

import com.hamlsy.liveklass_assignment.enrollment.application.usecase.*;
import com.hamlsy.liveklass_assignment.enrollment.presentation.request.CreateEnrollmentRequest;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/enrollments")
@RequiredArgsConstructor
public class EnrollmentController {

    private final CreateEnrollmentUseCase createEnrollmentUseCase;
    private final ConfirmPaymentUseCase confirmPaymentUseCase;
    private final CancelEnrollmentUseCase cancelEnrollmentUseCase;
    private final GetMyEnrollmentsUseCase getMyEnrollmentsUseCase;

    /**
     * 수강 신청
     * Idempotency-Key 헤더 지원 (선택):
     *   - 헤더 포함 시: 동일 키 재시도 → 캐시된 응답 반환
     *   - 헤더 없으면: 정상 처리 (단, 중복 신청은 AlreadyEnrolledException으로 차단)
     *
     * userId: 실제 환경에서는 SecurityContext에서 추출.
     *         과제에서는 요청 파라미터로 전달.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse createEnrollment(
        @RequestParam Long userId,
        @RequestBody CreateEnrollmentRequest request
    ) {
        return createEnrollmentUseCase.execute(userId, request);
    }

    @PatchMapping("/{enrollmentId}/confirm")
    public EnrollmentResponse confirmPayment(@PathVariable Long enrollmentId) {
        return confirmPaymentUseCase.execute(enrollmentId);
    }

    @PatchMapping("/{enrollmentId}/cancel")
    public EnrollmentResponse cancelEnrollment(
        @PathVariable Long enrollmentId,
        @RequestParam Long userId
    ) {
        return cancelEnrollmentUseCase.execute(enrollmentId, userId);
    }

    @GetMapping("/me")
    public List<EnrollmentResponse> getMyEnrollments(@RequestParam Long userId) {
        return getMyEnrollmentsUseCase.execute(userId);
    }
}
```

---

## 9. API 엔드포인트 요약

| Method | URI | 설명 | Idempotency-Key |
|---|---|---|---|
| POST | `/enrollments?userId={id}` | 수강 신청 | 선택 헤더 지원 |
| PATCH | `/enrollments/{id}/confirm` | 결제 확정 | Entity 레벨 멱등 |
| PATCH | `/enrollments/{id}/cancel?userId={id}` | 수강 취소 | Entity 레벨 멱등 |
| GET | `/enrollments/me?userId={id}` | 내 수강 내역 | - |

---

## 10. 멱등성 처리 흐름 요약

```
POST /enrollments (Idempotency-Key: abc-123)
    │
    ├─ [인터셉터] idempotencyCache.getIfPresent("abc-123")
    │     Hit  → 201 + 캐시 응답 반환 (컨트롤러 실행 없음)
    │     Miss → 컨트롤러 실행 진행
    │
    └─ [CreateEnrollmentUseCase]
          Cache 사전 검증 → Semaphore → Lock → 처리
          → idempotencyCache.put("abc-123", response)
          → 201 응답

PATCH /enrollments/{id}/confirm  (재시도)
    └─ [EnrollmentService.confirmPayment]
          enrollment.confirm()  → 이미 CONFIRMED → no-op 반환
          → 200 응답 (이전과 동일)

PATCH /enrollments/{id}/cancel   (재시도)
    └─ [EnrollmentService.cancelEnrollment]
          enrollment.cancel(userId) → 이미 CANCELLED → false 반환
          → course.decreaseCurrentCount() 호출 안 함
          → 200 응답 (이전과 동일)
```

---

## 완료 체크리스트

- [ ] EnrollmentService
  - [ ] createEnrollment (Lock 7단계 + 캐시 무효화)
  - [ ] confirmPayment (멱등: 이미 CONFIRMED → no-op)
  - [ ] cancelEnrollment (findByIdWithCourse + 별도 Lock으로 decreaseCurrentCount + 멱등)
  - [ ] getMyEnrollments (JOIN FETCH)
- [ ] CreateEnrollmentUseCase (3단계 방어 + 멱등성 키 저장)
- [ ] ConfirmPaymentUseCase
- [ ] CancelEnrollmentUseCase
- [ ] GetMyEnrollmentsUseCase
- [ ] CreateEnrollmentRequest, EnrollmentResponse
- [ ] EnrollmentController (Idempotency-Key 주석 포함)
