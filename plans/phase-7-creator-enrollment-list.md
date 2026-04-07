# Phase 7: 강의별 수강생 목록 조회 (크리에이터 전용)

## 목표
- Course에 creatorId 필드 추가 → 강의 소유권 부여
- Member에 role 필드 추가 (STUDENT / CREATOR)
- 크리에이터 본인 강의의 수강생 목록 조회 API
- 타인 강의 조회 시 접근 거부 예외

---

## 1. 패키지 구조 (추가/수정 파일)

```
member/
└── domain/
    └── entity/
        └── MemberRole.java           ← NEW (enum)
        └── Member.java               ← MODIFY (role 필드 추가)

course/
└── domain/
    └── entity/
        └── Course.java               ← MODIFY (creatorId 필드 추가)
└── presentation/
    └── request/
        └── CreateCourseRequest.java  ← MODIFY (creatorId 추가)
    └── response/
        └── CourseEnrollmentListResponse.java  ← NEW

enrollment/
├── domain/
│   └── repository/
│       └── EnrollmentRepository.java  ← MODIFY (메서드 추가)
├── infrastructure/
│   └── EnrollmentJpaRepository.java   ← MODIFY (쿼리 추가)
├── application/
│   └── usecase/
│       └── GetCourseEnrollmentsUseCase.java  ← NEW
└── presentation/
    └── EnrollmentController.java      ← MODIFY (엔드포인트 추가)

common/
└── exception/
    └── UnauthorizedCreatorException.java  ← NEW
```

---

## 2. 도메인 수정

### 2.1 MemberRole.java (Enum, NEW)

```java
package com.hamlsy.liveklass_assignment.member.domain.entity;

public enum MemberRole {
    STUDENT,    // 일반 수강생
    CREATOR     // 강의 크리에이터 (강의 개설 권한)
}
```

### 2.2 Member.java 수정 — role 필드 추가

```java
// 변경 전
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;

    @Builder
    public Member(String username) {
        this.username = username;
    }
}

// 변경 후
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberRole role;       // ← 추가

    @Builder
    public Member(String username, MemberRole role) {
        this.username = username;
        this.role = (role != null) ? role : MemberRole.STUDENT;
    }

    public boolean isCreator() {
        return this.role == MemberRole.CREATOR;
    }
}
```

### 2.3 Course.java 수정 — creatorId 필드 추가

```java
// Course 엔티티 @Table에 인덱스 추가
// (향후 "내가 만든 강의 목록" 조회 시 Full Scan 방지)
@Table(
    name = "course",
    indexes = {
        @Index(name = "idx_course_creator_id", columnList = "creator_id")
    }
)

// 필드 추가
@Column(nullable = false)
private Long creatorId;           // ← 추가 (Member.id 참조, FK 없이 Long으로 보관)

// Builder 수정
@Builder
public Course(String title, String description, int price,
              int capacity, LocalDateTime startDate, LocalDateTime endDate,
              Long creatorId) {   // ← creatorId 파라미터 추가
    validateDates(startDate, endDate);
    this.title        = title;
    this.description  = description;
    this.price        = price;
    this.capacity     = capacity;
    this.currentCount = 0;
    this.status       = CourseStatus.DRAFT;
    this.startDate    = startDate;
    this.endDate      = endDate;
    // creatorId가 null인 경우 0L로 기본 처리.
    // 기존 테스트 픽스처가 creatorId 없이 Course.builder()를 호출하므로
    // NOT NULL 제약 위반을 방지한다.
    this.creatorId    = (creatorId != null) ? creatorId : 0L;
}

// 도메인 메서드 추가
/** 요청자가 이 강의의 크리에이터인지 검증 */
public void validateCreator(Long requesterId) {
    if (!this.creatorId.equals(requesterId)) {
        throw new UnauthorizedCreatorException();
    }
}
```

> **왜 @ManyToOne 대신 Long으로 보관하는가**
>
> 과제 범위에서 Member는 최소 구현(id, username, role)이며
> 강의-회원 간 강결합을 피하고 도메인 간 독립성을 유지한다.
> creatorId로 충분히 소유권 검증이 가능하고, 필요 시 MemberRepository로 회원 정보를 조회할 수 있다.
>
> **왜 builder 기본값을 0L로 처리하는가**
>
> `@Column(nullable = false)` 추가 시 기존 테스트 4개(CreateEnrollmentUseCaseTest 등)가
> creatorId 없이 Course를 저장하므로 NOT NULL 위반이 발생한다.
> 기본값 0L은 "시스템 강의 / 소유자 미지정" 의미로 처리하고,
> 실제 강의 생성 시에는 creatorId를 반드시 전달한다.

---

## 3. CreateCourseRequest 수정

```java
// 변경 전
public record CreateCourseRequest(
    String title,
    String description,
    int price,
    int capacity,
    LocalDateTime startDate,
    LocalDateTime endDate
) {}

// 변경 후
public record CreateCourseRequest(
    Long creatorId,             // ← 추가 (실 서비스에서는 SecurityContext에서 추출)
    String title,
    String description,
    int price,
    int capacity,
    LocalDateTime startDate,
    LocalDateTime endDate
) {}
```

---

## 4. CreateCourseUseCase / CourseService 수정

```java
// CourseService.createCourse() 내 Course.builder() 수정
Course course = Course.builder()
    .creatorId(request.creatorId())   // ← 추가
    .title(request.title())
    .description(request.description())
    .price(request.price())
    .capacity(request.capacity())
    .startDate(request.startDate())
    .endDate(request.endDate())
    .build();
```

---

## 5. EnrollmentRepository 수정 — 강의별 수강생 조회 추가

### EnrollmentRepository.java (Interface)

```java
// 기존 메서드에 추가

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 강의별 수강생 목록 조회 (크리에이터 전용, 페이지네이션)
 * CANCELLED 제외: PENDING / CONFIRMED 수강생만 조회.
 *
 * JOIN FETCH 없음:
 *   응답 DTO(EnrollmentItem)는 e.getId(), e.getUserId(), e.getStatus(),
 *   e.getEnrolledAt(), e.getConfirmedAt()만 사용.
 *   courseTitle은 별도 로딩된 Course 객체에서 가져오므로 e.course 접근 불필요.
 *   불필요한 JOIN을 제거해 쿼리 단순화.
 */
Page<Enrollment> findPageByCourseIdAndStatusIn(
    Long courseId, List<EnrollmentStatus> statuses, Pageable pageable
);
```

### EnrollmentJpaRepository.java (Infrastructure)

```java
// 기존 메서드에 추가

/**
 * Enrollment → Course는 @ManyToOne(ToOne)이므로
 * JOIN FETCH + Pageable 조합은 안전하다 (HHH90003004 경고 없음).
 * 그러나 e.course 데이터를 실제로 사용하지 않으므로 JOIN FETCH 생략.
 * countQuery도 JOIN 없이 단순 COUNT로 처리.
 */
@Query(
    value = """
        SELECT e FROM Enrollment e
        WHERE e.course.id = :courseId
        AND e.status IN :statuses
        """,
    countQuery = """
        SELECT COUNT(e) FROM Enrollment e
        WHERE e.course.id = :courseId
        AND e.status IN :statuses
        """
)
Page<Enrollment> findPageByCourseIdAndStatusIn(
    @Param("courseId") Long courseId,
    @Param("statuses") List<EnrollmentStatus> statuses,
    Pageable pageable
);
```

> `CANCELLED` 상태는 수강생 목록에서 제외한다.
> 정렬 기본값은 Controller의 `@PageableDefault(sort = "enrolledAt")` 에서 지정.
> 인기 강의 수강생 수천 건을 전부 로딩하는 Unbounded 쿼리를 Page로 교체.

---

## 6. 예외 클래스

### UnauthorizedCreatorException.java

```java
package com.hamlsy.liveklass_assignment.common.exception;

public class UnauthorizedCreatorException extends BusinessException {
    public UnauthorizedCreatorException() {
        super(ErrorCode.UNAUTHORIZED_CREATOR);
    }
}
```

### ErrorCode 추가 항목

```java
// ErrorCode.java 에 추가
UNAUTHORIZED_CREATOR("UNAUTHORIZED_CREATOR", "해당 강의의 크리에이터만 조회할 수 있습니다."),
MEMBER_NOT_FOUND("MEMBER_NOT_FOUND", "회원을 찾을 수 없습니다."),
```

---

## 7. Response DTO

### CourseEnrollmentListResponse.java (NEW)

```java
package com.hamlsy.liveklass_assignment.course.presentation.response;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import java.time.LocalDateTime;
import java.util.List;

public record CourseEnrollmentListResponse(
    Long courseId,
    String courseTitle,
    long totalElements,     // 전체 수강생 수 (Page.getTotalElements())
    int totalPages,
    int page,
    int size,
    boolean hasNext,
    List<EnrollmentItem> enrollments
) {

    public record EnrollmentItem(
        Long enrollmentId,
        Long userId,
        EnrollmentStatus status,
        LocalDateTime enrolledAt,
        LocalDateTime confirmedAt
    ) {
        public static EnrollmentItem from(Enrollment e) {
            return new EnrollmentItem(
                e.getId(),
                e.getUserId(),
                e.getStatus(),
                e.getEnrolledAt(),
                e.getConfirmedAt()
            );
        }
    }

    public static CourseEnrollmentListResponse of(Long courseId, String courseTitle,
                                                   Page<Enrollment> page) {
        List<EnrollmentItem> items = page.getContent().stream()
            .map(EnrollmentItem::from)
            .toList();
        return new CourseEnrollmentListResponse(
            courseId,
            courseTitle,
            page.getTotalElements(),
            page.getTotalPages(),
            page.getNumber(),
            page.getSize(),
            page.hasNext(),
            items
        );
    }
}
```

---

## 8. GetCourseEnrollmentsUseCase (NEW)

```java
package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.course.presentation.response.CourseEnrollmentListResponse;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import com.hamlsy.liveklass_assignment.member.domain.entity.Member;
import com.hamlsy.liveklass_assignment.member.domain.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class GetCourseEnrollmentsUseCase {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final MemberRepository memberRepository;
    private final Cache<Long, MemberRole> memberRoleCache;  // TTL 60초

    /**
     * 강의별 수강생 목록 조회 (크리에이터 전용, 페이지네이션)
     *
     * 처리 순서:
     *   1. 강의 조회 → 없으면 CourseNotFoundException
     *   2. 요청자 CREATOR 역할 검증
     *      - memberRoleCache hit 시 DB 미접근
     *      - miss 시 MemberRepository 조회 후 캐시 저장
     *   3. 강의 소유권 검증 (course.validateCreator)
     *   4. PENDING / CONFIRMED 수강생 목록 페이지 조회 (CANCELLED 제외)
     *   5. CourseEnrollmentListResponse 반환
     *
     * @param courseId    조회할 강의 ID
     * @param requesterId 요청자 Member ID (실 서비스에서는 SecurityContext에서 추출)
     * @param pageable    페이지 정보 (page, size, sort)
     */
    @Transactional(readOnly = true)
    public CourseEnrollmentListResponse execute(Long courseId, Long requesterId, Pageable pageable) {

        // 1. 강의 조회
        Course course = courseRepository.findById(courseId)
            .orElseThrow(CourseNotFoundException::new);

        // 2. CREATOR 역할 검증 (캐시 우선)
        MemberRole role = memberRoleCache.getIfPresent(requesterId);
        if (role == null) {
            Member requester = memberRepository.findById(requesterId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
            role = requester.getRole();
            memberRoleCache.put(requesterId, role);
        }
        if (role != MemberRole.CREATOR) {
            throw new UnauthorizedCreatorException();
        }

        // 3. 강의 소유권 검증 (course.creatorId == requesterId)
        course.validateCreator(requesterId);

        // 4. 수강생 목록 페이지 조회 (CANCELLED 제외, JOIN FETCH 없음)
        Page<Enrollment> page = enrollmentRepository.findPageByCourseIdAndStatusIn(
            courseId,
            List.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED),
            pageable
        );

        return CourseEnrollmentListResponse.of(courseId, course.getTitle(), page);
    }
}
```

---

## 9. EnrollmentController 수정 — 엔드포인트 추가

```java
// EnrollmentController에 추가

private final GetCourseEnrollmentsUseCase getCourseEnrollmentsUseCase;

/**
 * 강의별 수강생 목록 조회 (크리에이터 전용, 페이지네이션)
 *
 * 기본값: page=0, size=20, sort=enrolledAt ASC (신청 순서)
 * 예시:
 *   GET /enrollments/courses/1/enrollments?creatorId=10
 *   GET /enrollments/courses/1/enrollments?creatorId=10&page=1&size=10
 *
 * @param courseId    강의 ID
 * @param creatorId   요청자 ID (실 서비스: SecurityContext에서 추출)
 * @param pageable    Spring MVC가 query string → Pageable 자동 변환
 */
@GetMapping("/courses/{courseId}/enrollments")
public CourseEnrollmentListResponse getCourseEnrollments(
    @PathVariable Long courseId,
    @RequestParam Long creatorId,
    @PageableDefault(size = 20, sort = "enrolledAt", direction = Sort.Direction.ASC)
    Pageable pageable
) {
    return getCourseEnrollmentsUseCase.execute(courseId, creatorId, pageable);
}
```

> URI 설계 원칙: 수강생 목록은 강의(course) 하위 리소스이므로
> `/courses/{courseId}/enrollments` 로 배치한다.
> EnrollmentController에 추가하되, CourseController 이동도 가능하다.

---

## 10. MemberRepository — findById 확인

기존 `MemberRepository`에 `findById(Long id): Optional<Member>` 가 있으면 그대로 사용.
없으면 추가:

```java
// MemberRepository.java (interface)
Optional<Member> findById(Long id);
```

---

## 10-1. CacheConfig — memberRoleCache Bean 추가

`GetCourseEnrollmentsUseCase`가 사용하는 Member role 캐시 Bean을 `CacheConfig.java`에 추가한다.

```java
// CacheConfig.java 에 추가

/**
 * Member role 캐시 (per memberId)
 * TTL 60초: role은 거의 변하지 않는 정적 데이터.
 * 크리에이터가 반복 조회 시 Member SELECT 제거.
 */
@Bean
public Cache<Long, MemberRole> memberRoleCache() {
    return Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build();
}
```

---

## 11. GlobalExceptionHandler 추가 항목

| Exception | HTTP | 상황 |
|---|---|---|
| `UnauthorizedCreatorException` | 403 | 크리에이터 권한 없음 / 타인 강의 접근 |
| `BusinessException(MEMBER_NOT_FOUND)` | 404 | 회원 없음 |

---

## 12. API 엔드포인트 요약

| Method | URI | 설명 | 권한 |
|---|---|---|---|
| GET | `/courses/{courseId}/enrollments?creatorId={id}` | 강의별 수강생 목록 | 크리에이터 전용 |

---

## 13. 구현 순서

1. `MemberRole.java` (enum)
2. `Member.java` — role 필드 추가 (builder 기본값 STUDENT), `isCreator()`, `getRole()` 메서드
3. `Course.java` — creatorId 필드 추가 (builder 기본값 0L), `validateCreator()`, `@Index` 추가
4. `CreateCourseRequest.java` — creatorId 파라미터 추가
5. `CourseService.createCourse()` — Course.builder()에 creatorId 전달
6. `UnauthorizedCreatorException.java` + `ErrorCode` 항목 추가
7. `CacheConfig.java` — `memberRoleCache` Bean 추가
8. `EnrollmentRepository` 인터페이스 — `findPageByCourseIdAndStatusIn()` 추가
9. `EnrollmentJpaRepository` — JPQL + countQuery 구현 (JOIN FETCH 없음)
10. `CourseEnrollmentListResponse.java` (Page 기반)
11. `GetCourseEnrollmentsUseCase.java` (memberRoleCache + Pageable)
12. `EnrollmentController` — `@PageableDefault` 적용 엔드포인트 추가
13. 테스트: 크리에이터 조회 성공 / 비크리에이터 403 / 타인 강의 403

## 14. 완료 체크리스트

- [ ] MemberRole enum
- [ ] Member.java role 필드 (builder 기본값 STUDENT) + isCreator() + getRole()
- [ ] Course.java creatorId 필드 (builder 기본값 0L) + validateCreator() + @Index
- [ ] CreateCourseRequest creatorId 추가
- [ ] CourseService creatorId 전달
- [ ] UnauthorizedCreatorException + ErrorCode (UNAUTHORIZED_CREATOR, MEMBER_NOT_FOUND)
- [ ] CacheConfig — memberRoleCache Bean
- [ ] EnrollmentRepository findPageByCourseIdAndStatusIn() (Page 반환)
- [ ] EnrollmentJpaRepository JPQL + countQuery (JOIN FETCH 없음)
- [ ] CourseEnrollmentListResponse DTO (Page 메타데이터 포함)
- [ ] GetCourseEnrollmentsUseCase (memberRoleCache + Pageable)
- [ ] EnrollmentController @PageableDefault 적용
- [ ] GlobalExceptionHandler 수정
