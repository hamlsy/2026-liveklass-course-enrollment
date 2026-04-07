# Phase 8: 신청 내역 페이지네이션

## 목표
- `GET /enrollments/me` 에 페이지네이션 적용
- Spring Data JPA `Pageable` 통합
- 페이지 메타데이터 포함 응답 래퍼 (`PageResponse<T>`)
- `enrolledAt` 기준 내림차순 기본 정렬

---

## 시니어 검토 반영 사항 (수정 이유)

| # | 문제 | 조치 |
|---|------|------|
| 1 | `(user_id, enrolled_at)` 복합 인덱스 없음 → 정렬 시 filesort 발생 | `Enrollment.java`에 인덱스 추가 |
| 2 | `getMyEnrollments(List)` Phase 8 이후 dead code로 전락 | Phase 8 후 삭제 |
| 3 | 클라이언트가 임의 sort 필드 전달 가능 → 인덱스 없는 컬럼 정렬 시 DB 부하 | 현재 과제 범위: `enrolledAt`만 허용으로 문서화 |
| 4 | `max-page-size` 미설정 → `?size=100000` 허용 | `application.yaml`에 `max-page-size: 100` 추가 |

---

## 1. 변경 파일 목록

```
common/
└── response/
    └── PageResponse.java              ← NEW (페이지 응답 래퍼)

enrollment/
├── domain/
│   ├── entity/
│   │   └── Enrollment.java            ← MODIFY (복합 인덱스 추가)
│   └── repository/
│       └── EnrollmentRepository.java  ← MODIFY (Page 반환 메서드 추가)
├── infrastructure/
│   └── EnrollmentJpaRepository.java   ← MODIFY (Pageable 쿼리 추가)
├── application/
│   ├── service/
│   │   └── EnrollmentService.java     ← MODIFY (getMyEnrollmentsPage 추가, getMyEnrollments 삭제)
│   └── usecase/
│       └── GetMyEnrollmentsUseCase.java  ← MODIFY (Pageable 파라미터 적용)
└── presentation/
    └── EnrollmentController.java      ← MODIFY (@PageableDefault 적용)

src/main/resources/
└── application.yaml                   ← MODIFY (max-page-size 추가)
```

---

## 2. Enrollment.java — 복합 인덱스 추가

기존 `idx_enrollment_user_id (user_id)` 단독 인덱스는 정렬 없는 조회에만 최적.

`WHERE user_id = ? ORDER BY enrolled_at DESC LIMIT 10` 실행 시:
- 기존: user_id 필터 → 전체 결과 filesort → LIMIT
- 신규: `(user_id, enrolled_at)` 복합 인덱스 → Index Range Scan → 순서대로 LIMIT

```java
// 기존 indexes에 추가
@Index(name = "idx_enrollment_user_enrolled_at", columnList = "user_id, enrolled_at")
```

> `idx_enrollment_user_id`는 유지 (단순 EXISTS, findAll 등 sort 없는 쿼리에서 더 좁은 인덱스 활용).

---

## 3. PageResponse<T> (공통 응답 래퍼)

```java
package com.hamlsy.liveklass_assignment.common.response;

import org.springframework.data.domain.Page;
import java.util.List;

public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.hasNext()
        );
    }
}
```

---

## 4. EnrollmentRepository — Pageable 메서드 추가

```java
/**
 * 내 수강 내역 페이지네이션 조회 (N+1 방지)
 * JOIN FETCH + Pageable 조합 (ToOne → 행 증가 없음, 안전).
 * countQuery 분리로 COUNT 쿼리에서 불필요한 JOIN 제거.
 */
Page<Enrollment> findPageByUserIdWithCourse(Long userId, Pageable pageable);
```

---

## 5. EnrollmentJpaRepository — JPQL + countQuery

```java
/**
 * JOIN FETCH + Pageable (ToOne) — HHH90003004 경고 없음.
 * Enrollment → Course는 @ManyToOne이므로 행 증가 없이 안전.
 * countQuery 별도 지정으로 COUNT 쿼리에서 JOIN 제거.
 *
 * 인덱스 활용: idx_enrollment_user_enrolled_at (user_id, enrolled_at)
 *   → WHERE user_id = ? ORDER BY enrolled_at DESC LIMIT ? OFFSET ?
 *   → Index Range Scan, filesort 없음.
 */
@Query(
    value = """
        SELECT e FROM Enrollment e
        JOIN FETCH e.course
        WHERE e.userId = :userId
        """,
    countQuery = """
        SELECT COUNT(e) FROM Enrollment e
        WHERE e.userId = :userId
        """
)
Page<Enrollment> findPageByUserIdWithCourse(
    @Param("userId") Long userId,
    Pageable pageable
);
```

---

## 6. EnrollmentService — 수정

```java
// 추가
@Transactional(readOnly = true)
public Page<Enrollment> getMyEnrollmentsPage(Long userId, Pageable pageable) {
    return enrollmentRepository.findPageByUserIdWithCourse(userId, pageable);
}

// 삭제 — Phase 8 이후 dead code
// getMyEnrollments(Long userId): List<Enrollment>
//   → GetMyEnrollmentsUseCase에서만 사용, cancelEnrollment는 findByIdWithCourse 직접 사용
```

---

## 7. GetMyEnrollmentsUseCase — Pageable 적용

```java
public PageResponse<EnrollmentResponse> execute(Long userId, Pageable pageable) {
    Page<Enrollment> page = enrollmentService.getMyEnrollmentsPage(userId, pageable);
    Page<EnrollmentResponse> mapped = page.map(EnrollmentResponse::from);
    return PageResponse.from(mapped);
}
```

---

## 8. EnrollmentController — @PageableDefault 적용

```java
/**
 * 기본값: page=0, size=10, sort=enrolledAt DESC
 * max-page-size: 100 (application.yaml)
 *
 * 허용 sort 필드: enrolledAt (인덱스 지원)
 * 비허용 필드(status, confirmedAt 등) 전달 시 filesort 발생 — 과제 범위: 허용
 */
@GetMapping("/me")
public PageResponse<EnrollmentResponse> getMyEnrollments(
    @RequestParam Long userId,
    @PageableDefault(size = 10, sort = "enrolledAt", direction = Sort.Direction.DESC)
    Pageable pageable
) {
    return getMyEnrollmentsUseCase.execute(userId, pageable);
}
```

---

## 9. application.yaml — max-page-size 추가

```yaml
spring:
  data:
    web:
      pageable:
        max-page-size: 100
```

---

## 10. 구현 순서

1. `Enrollment.java` — 복합 인덱스 추가
2. `PageResponse.java` — 공통 래퍼 생성
3. `EnrollmentRepository.java` — Page 반환 메서드 추가
4. `EnrollmentJpaRepository.java` — JPQL + countQuery 구현
5. `EnrollmentService.java` — getMyEnrollmentsPage 추가, getMyEnrollments 삭제
6. `GetMyEnrollmentsUseCase.java` — Pageable 파라미터 적용
7. `EnrollmentController.java` — @PageableDefault 적용
8. `application.yaml` — max-page-size 추가

---

## 11. 완료 체크리스트

- [ ] Enrollment — (user_id, enrolled_at) 복합 인덱스
- [ ] PageResponse<T> 공통 래퍼
- [ ] EnrollmentRepository — Page 반환 메서드
- [ ] EnrollmentJpaRepository — JPQL + countQuery
- [ ] EnrollmentService — getMyEnrollmentsPage / getMyEnrollments 삭제
- [ ] GetMyEnrollmentsUseCase — Pageable 파라미터 적용
- [ ] EnrollmentController — @PageableDefault 적용
- [ ] application.yaml — max-page-size: 100
