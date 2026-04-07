# Phase 3: Enrollment 도메인 구현

## 목표
- Enrollment 도메인 레이어 전체 구현 (entity, repository, infrastructure)
- N+1 방지 쿼리 설계
- 인덱스 선언
- confirm/cancel 멱등성 처리 (Entity 레벨)

---

## 1. 패키지 구조

```
enrollment/
├── domain/
│   ├── entity/
│   │   ├── Enrollment.java
│   │   └── EnrollmentStatus.java
│   ├── repository/
│   │   └── EnrollmentRepository.java
│   └── service/
│       └── EnrollmentDomainService.java   ← 중복 신청 검증
└── infrastructure/
    └── EnrollmentJpaRepository.java
```

---

## 2. EnrollmentStatus (Enum)

```java
package com.hamlsy.liveklass_assignment.enrollment.domain.entity;

public enum EnrollmentStatus {
    PENDING,      // 신청 완료, 결제 대기
    CONFIRMED,    // 결제 완료
    CANCELLED;    // 취소됨

    public boolean isPending()     { return this == PENDING; }
    public boolean isConfirmed()   { return this == CONFIRMED; }
    public boolean isCancelled()   { return this == CANCELLED; }

    /** 취소 가능 상태: CONFIRMED 만 */
    public boolean isCancellable() { return this == CONFIRMED; }
}
```

---

## 3. Enrollment Entity

```java
package com.hamlsy.liveklass_assignment.enrollment.domain.entity;

import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "enrollment",
    indexes = {
        // 내 수강 내역 조회: WHERE user_id = ?
        @Index(name = "idx_enrollment_user_id", columnList = "user_id"),

        // 강의별 신청 조회: WHERE course_id = ?
        @Index(name = "idx_enrollment_course_id", columnList = "course_id"),

        // 중복 신청 검증: WHERE user_id = ? AND course_id = ? AND status IN (...)
        // Covering Index — 인덱스만으로 EXISTS 처리 (테이블 Row 접근 없음)
        @Index(name = "idx_enrollment_user_course_status",
               columnList = "user_id, course_id, status")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * userId는 Member FK 대신 Long으로 보관.
     * → 조회 시 Member JOIN 불필요, N+1 원천 차단.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EnrollmentStatus status;

    @Column(nullable = false)
    private LocalDateTime enrolledAt;

    private LocalDateTime confirmedAt;

    @Builder
    public Enrollment(Long userId, Course course) {
        this.userId     = userId;
        this.course     = course;
        this.status     = EnrollmentStatus.PENDING;
        this.enrolledAt = LocalDateTime.now();
    }

    // ── 도메인 메서드 ──────────────────────────────────────────────

    /**
     * 결제 확정: PENDING → CONFIRMED
     *
     * [멱등성] 이미 CONFIRMED 상태이면 아무 동작 없이 반환.
     * 클라이언트가 네트워크 오류로 confirm 요청을 재시도할 때 안전하게 처리.
     */
    public void confirm() {
        if (this.status.isConfirmed()) {
            return;  // 멱등: 이미 처리됨, 예외 없이 반환
        }
        if (!this.status.isPending()) {
            // CANCELLED 상태에서 confirm 시도 → 오류
            throw new BusinessException(ErrorCode.INVALID_ENROLLMENT_STATUS);
        }
        this.status     = EnrollmentStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    /**
     * 수강 취소
     * 1. 소유자 검증
     * 2. 이미 CANCELLED 면 멱등 반환 (재시도 안전)
     * 3. CONFIRMED 상태인지 검증
     * 4. 결제일(confirmedAt) 기준 7일 이내인지 검증
     * 5. CANCELLED 로 변경
     *
     * [멱등성] 이미 CANCELLED 상태이면 예외 없이 반환.
     * 단, 소유자 검증은 CANCELLED 이후에도 수행 (타인이 재시도하는 경우 방지).
     *
     * @return true = 실제 취소 처리됨, false = 이미 취소된 상태 (멱등 응답)
     */
    public boolean cancel(Long requestUserId) {
        validateOwnership(requestUserId);

        if (this.status.isCancelled()) {
            return false;  // 멱등: 이미 취소됨
        }

        validateCancellable();
        validateCancellationPeriod();
        this.status = EnrollmentStatus.CANCELLED;
        return true;  // 실제 취소 처리됨
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    // ── private ───────────────────────────────────────────────────

    private void validateOwnership(Long requestUserId) {
        if (!isOwnedBy(requestUserId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_ENROLLMENT);
        }
    }

    private void validateCancellable() {
        if (!this.status.isCancellable()) {
            throw new BusinessException(ErrorCode.INVALID_ENROLLMENT_STATUS);
        }
    }

    private void validateCancellationPeriod() {
        if (confirmedAt == null) return;
        if (LocalDateTime.now().isAfter(confirmedAt.plusDays(7))) {
            throw new CancellationPeriodExpiredException();
        }
    }
}
```

> **cancel() 반환값 설계 이유**
> `cancel()`이 `boolean`을 반환하는 이유는 Service 레이어에서
> 실제 취소가 발생했는지(true) vs 멱등 응답인지(false)를 구분해야
> `course.decreaseCurrentCount()` 호출 여부를 결정할 수 있기 때문이다.
> 이미 CANCELLED인 경우 currentCount를 다시 감소시키면 정합성이 깨진다.

---

## 4. EnrollmentRepository (Interface)

```java
package com.hamlsy.liveklass_assignment.enrollment.domain.repository;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository {

    Enrollment save(Enrollment enrollment);

    Optional<Enrollment> findById(Long id);

    /**
     * 중복 신청 검증
     * idx_enrollment_user_course_status 인덱스 활용 (Covering Index)
     */
    boolean existsByUserIdAndCourseIdAndStatusIn(
        Long userId, Long courseId, List<EnrollmentStatus> statuses
    );

    /**
     * 내 수강 내역 조회 (N+1 방지)
     * JOIN FETCH로 course를 단일 쿼리로 로딩
     */
    List<Enrollment> findAllByUserIdWithCourse(Long userId);

    /**
     * 취소 처리용 — enrollment + course 를 함께 로딩
     * cancelEnrollment에서 enrollment와 course 모두 필요하므로
     * JOIN FETCH로 단일 쿼리 처리 (암묵적 LAZY 로딩 방지)
     */
    Optional<Enrollment> findByIdWithCourse(Long enrollmentId);
}
```

---

## 5. EnrollmentJpaRepository (Infrastructure)

```java
package com.hamlsy.liveklass_assignment.enrollment.infrastructure;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface EnrollmentJpaRepository
        extends JpaRepository<Enrollment, Long>, EnrollmentRepository {

    /**
     * 중복 신청 검증
     * Spring Data JPA 메서드 이름 쿼리 — idx_enrollment_user_course_status 활용
     */
    boolean existsByUserIdAndCourseIdAndStatusIn(
        Long userId, Long courseId, List<EnrollmentStatus> statuses
    );

    /**
     * N+1 방지: JOIN FETCH로 course를 함께 로딩
     *
     * 단순 findAllByUserId 사용 시 문제:
     *   SELECT * FROM enrollment WHERE user_id = ?   → 1번
     *   SELECT * FROM course WHERE id = ?            → N번 추가 (N+1 문제)
     *
     * JOIN FETCH 사용 시:
     *   SELECT e.*, c.* FROM enrollment e
     *   INNER JOIN course c ON e.course_id = c.id
     *   WHERE e.user_id = ?                          → 단일 쿼리로 해결
     */
    @Query("SELECT e FROM Enrollment e JOIN FETCH e.course WHERE e.userId = :userId")
    List<Enrollment> findAllByUserIdWithCourse(@Param("userId") Long userId);

    /**
     * 취소 처리용 — enrollment + course 단일 쿼리
     *
     * cancelEnrollment 흐름에서 enrollment.cancel() 후 course.decreaseCurrentCount() 호출.
     * course를 명시적 JOIN FETCH로 미리 로딩하여 암묵적 LAZY 쿼리 방지.
     */
    @Query("SELECT e FROM Enrollment e JOIN FETCH e.course WHERE e.id = :enrollmentId")
    Optional<Enrollment> findByIdWithCourse(@Param("enrollmentId") Long enrollmentId);
}
```

---

## 6. EnrollmentDomainService

```java
package com.hamlsy.liveklass_assignment.enrollment.domain.service;

import com.hamlsy.liveklass_assignment.common.exception.AlreadyEnrolledException;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EnrollmentDomainService {

    private final EnrollmentRepository enrollmentRepository;

    /**
     * 중복 신청 검증
     * PENDING 또는 CONFIRMED 상태의 신청이 이미 존재하면 AlreadyEnrolledException.
     * CANCELLED는 포함하지 않음 — 취소 후 재신청은 허용.
     */
    public void validateNoDuplicateEnrollment(Long userId, Long courseId) {
        boolean exists = enrollmentRepository.existsByUserIdAndCourseIdAndStatusIn(
            userId, courseId,
            List.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED)
        );
        if (exists) {
            throw new AlreadyEnrolledException();
        }
    }
}
```

---

## 7. N+1 발생 시나리오 vs 해결

### 발생 시나리오

```java
// ❌ 잘못된 구현 — N+1 발생
List<Enrollment> enrollments = enrollmentRepository.findAllByUserId(userId);
// → SELECT * FROM enrollment WHERE user_id = ?  (1번)

for (Enrollment e : enrollments) {
    String title = e.getCourse().getTitle();  // LAZY 트리거
    // → SELECT * FROM course WHERE id = ?     (N번 추가)
}
// 총 쿼리 수 = 1 + N
```

### 해결

```java
// ✅ JOIN FETCH — 단일 쿼리
List<Enrollment> enrollments = enrollmentRepository.findAllByUserIdWithCourse(userId);
// → SELECT e.*, c.* FROM enrollment e
//   INNER JOIN course c ON e.course_id = c.id
//   WHERE e.user_id = ?   (1번으로 완결)
```

### 취소 시 암묵적 LAZY 로딩 방지

```java
// ❌ 잘못된 구현 — cancelEnrollment 내부에서 암묵적 LAZY 로딩
Enrollment enrollment = enrollmentRepository.findById(enrollmentId).orElseThrow();
enrollment.cancel(userId);
enrollment.getCourse().decreaseCurrentCount();  // 여기서 SELECT course WHERE id = ? 추가 발생

// ✅ 올바른 구현 — findByIdWithCourse로 한 번에 로딩
Enrollment enrollment = enrollmentRepository.findByIdWithCourse(enrollmentId).orElseThrow();
enrollment.cancel(userId);
enrollment.getCourse().decreaseCurrentCount();  // 이미 로딩됨, 추가 쿼리 없음
```

---

## 8. 인덱스 상세 설명

| 인덱스명 | 컬럼 | 사용 쿼리 | 효과 |
|---|---|---|---|
| `idx_enrollment_user_id` | `user_id` | `findAllByUserIdWithCourse` | Full Scan → Index Scan |
| `idx_enrollment_course_id` | `course_id` | 강의별 신청 조회 | Full Scan → Index Scan |
| `idx_enrollment_user_course_status` | `user_id, course_id, status` | `existsByUserIdAndCourseIdAndStatusIn` | Covering Index |

> **Covering Index**: `(user_id, course_id, status)` 복합 인덱스는 EXISTS 쿼리의
> WHERE 조건과 필요한 컬럼을 모두 포함한다.
> B-Tree 인덱스만 탐색하고 실제 테이블 Row를 읽지 않아 I/O 최소화.

---

## 완료 체크리스트

- [ ] EnrollmentStatus enum (isPending, isConfirmed, isCancelled, isCancellable)
- [ ] Enrollment entity
  - [ ] Builder (PENDING 초기화)
  - [ ] confirm() — CONFIRMED이면 멱등 반환, CANCELLED이면 예외
  - [ ] cancel(userId) — boolean 반환 (실제처리 vs 멱등), 소유자+상태+7일 검증
  - [ ] isOwnedBy(userId)
- [ ] 인덱스 3개 선언 (@Table indexes)
- [ ] EnrollmentRepository interface
  - [ ] existsByUserIdAndCourseIdAndStatusIn
  - [ ] findAllByUserIdWithCourse (JOIN FETCH)
  - [ ] findByIdWithCourse (취소용 JOIN FETCH)
- [ ] EnrollmentJpaRepository (구현체)
- [ ] EnrollmentDomainService (중복 신청 검증, CANCELLED 재신청 허용)
