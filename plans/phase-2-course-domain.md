# Phase 2: Course 도메인 구현

## 목표
- Course 도메인 전체 구현 (domain, infrastructure, application, presentation)
- Member 최소 구현

---

## 1. 패키지 구조

```
member/
├── domain/
│   ├── entity/
│   │   └── Member.java
│   └── repository/
│       └── MemberRepository.java
└── infrastructure/
    └── MemberJpaRepository.java

course/
├── domain/
│   ├── entity/
│   │   ├── Course.java
│   │   └── CourseStatus.java
│   ├── repository/
│   │   └── CourseRepository.java
│   └── service/
│       └── (CourseStatus 전이 규칙이 Enum에 내장되어 별도 DomainService 불필요)
├── infrastructure/
│   └── CourseJpaRepository.java
├── application/
│   ├── service/
│   │   └── CourseService.java
│   └── usecase/
│       ├── CreateCourseUseCase.java
│       ├── UpdateCourseStatusUseCase.java
│       ├── GetCourseListUseCase.java
│       └── GetCourseDetailUseCase.java
└── presentation/
    ├── CourseController.java
    ├── request/
    │   ├── CreateCourseRequest.java
    │   └── UpdateCourseStatusRequest.java
    └── response/
        ├── CourseDetailResponse.java
        └── CourseListResponse.java
```

---

## 2. Member (최소 구현)

```java
// Member.java
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;

    @Builder
    public Member(String username) { this.username = username; }
}

// MemberRepository.java
public interface MemberRepository {
    Member save(Member member);
    Optional<Member> findById(Long id);
}

// MemberJpaRepository.java
public interface MemberJpaRepository extends JpaRepository<Member, Long>, MemberRepository {}
```

---

## 3. CourseStatus (Enum)

```java
package com.hamlsy.liveklass_assignment.course.domain.entity;

public enum CourseStatus {
    DRAFT,
    OPEN,
    CLOSED;

    /**
     * 상태 전이 규칙:
     *   DRAFT  → OPEN   (모집 시작)
     *   OPEN   → CLOSED (마감)
     *   CLOSED → (불가) — 한번 닫힌 강의는 재오픈 불가
     */
    public boolean canTransitionTo(CourseStatus next) {
        return switch (this) {
            case DRAFT  -> next == OPEN;
            case OPEN   -> next == CLOSED;
            case CLOSED -> false;
        };
    }
}
```

---

## 4. Course Entity

```java
package com.hamlsy.liveklass_assignment.course.domain.entity;

import com.hamlsy.liveklass_assignment.common.exception.*;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "course",
    indexes = {
        @Index(name = "idx_course_status", columnList = "status")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Course {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private int currentCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CourseStatus status;

    @Column(nullable = false)
    private LocalDateTime startDate;

    @Column(nullable = false)
    private LocalDateTime endDate;

    @Builder
    public Course(String title, String description, int price,
                  int capacity, LocalDateTime startDate, LocalDateTime endDate) {
        validateDates(startDate, endDate);
        this.title       = title;
        this.description = description;
        this.price       = price;
        this.capacity    = capacity;
        this.currentCount = 0;
        this.status      = CourseStatus.DRAFT;
        this.startDate   = startDate;
        this.endDate     = endDate;
    }

    // ── 도메인 메서드 ──────────────────────────────────────────────

    /** 상태가 OPEN인지 검증 */
    public void validateOpen() {
        if (this.status != CourseStatus.OPEN) {
            throw new InvalidCourseStatusException();
        }
    }

    /** 현재 시각이 수강 신청 기간 내인지 검증 */
    public void validateEnrollmentPeriod() {
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(startDate) || now.isAfter(endDate)) {
            throw new InvalidCourseStatusException(ErrorCode.INVALID_ENROLLMENT_PERIOD);
        }
    }

    /**
     * 정원을 1 증가.
     * 반드시 Pessimistic Lock 획득 후 호출해야 한다.
     * currentCount >= capacity 이면 EnrollmentLimitExceededException
     */
    public void increaseCurrentCount() {
        if (this.currentCount >= this.capacity) {
            throw new EnrollmentLimitExceededException();
        }
        this.currentCount++;
    }

    /**
     * 정원을 1 감소 (취소 시 호출).
     * 반드시 Pessimistic Lock 획득 후 호출해야 한다.
     */
    public void decreaseCurrentCount() {
        if (this.currentCount <= 0) return; // 방어 코드
        this.currentCount--;
    }

    /**
     * 상태 전이.
     * canTransitionTo() 로 규칙 검증 — CLOSED → 어떤 상태로도 불가.
     */
    public void changeStatus(CourseStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new InvalidCourseStatusException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        this.status = next;
    }

    // ── private ───────────────────────────────────────────────────

    private static void validateDates(LocalDateTime startDate, LocalDateTime endDate) {
        if (endDate == null || startDate == null
                || !endDate.isAfter(startDate)) {
            throw new InvalidCourseStatusException(ErrorCode.INVALID_COURSE_DATE);
        }
    }
}
```

---

## 5. CourseRepository (Interface)

```java
package com.hamlsy.liveklass_assignment.course.domain.repository;

import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import java.util.List;
import java.util.Optional;

public interface CourseRepository {
    Course save(Course course);
    Optional<Course> findById(Long id);
    List<Course> findAll();
    List<Course> findAllByStatus(CourseStatus status);

    /**
     * Pessimistic Write Lock + 3초 타임아웃.
     * 수강 신청 / 취소 시 currentCount 수정에 사용.
     * lock.timeout = 3000ms — 3초 초과 대기 시 즉시 예외 반환.
     */
    Optional<Course> findByIdWithLock(Long id);
}
```

---

## 6. CourseJpaRepository (Infrastructure)

```java
package com.hamlsy.liveklass_assignment.course.infrastructure;

import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import java.util.List;
import java.util.Optional;

public interface CourseJpaRepository extends JpaRepository<Course, Long>, CourseRepository {

    List<Course> findAllByStatus(CourseStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(
        name  = "jakarta.persistence.lock.timeout",
        value = "3000"   // 3초 초과 → PessimisticLockException
    ))
    @Query("SELECT c FROM Course c WHERE c.id = :id")
    Optional<Course> findByIdWithLock(Long id);
}
```

---

## 7. CourseService (Application Service)

```java
package com.hamlsy.liveklass_assignment.course.application.service;

import com.hamlsy.liveklass_assignment.common.exception.CourseNotFoundException;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;

    @Transactional
    public Course create(Course course) {
        return courseRepository.save(course);
    }

    @Transactional
    public Course updateStatus(Long courseId, CourseStatus nextStatus) {
        Course course = courseRepository.findById(courseId)
            .orElseThrow(CourseNotFoundException::new);
        course.changeStatus(nextStatus);
        return course; // dirty checking
    }

    @Transactional(readOnly = true)
    public Course findById(Long courseId) {
        return courseRepository.findById(courseId)
            .orElseThrow(CourseNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<Course> findAll(CourseStatus status) {
        return (status != null)
            ? courseRepository.findAllByStatus(status)
            : courseRepository.findAll();
    }
}
```

---

## 8. Request / Response DTO

```java
// CreateCourseRequest.java
public record CreateCourseRequest(
    String title,
    String description,
    int price,
    int capacity,
    LocalDateTime startDate,
    LocalDateTime endDate
) {}

// UpdateCourseStatusRequest.java
public record UpdateCourseStatusRequest(CourseStatus status) {}

// CourseDetailResponse.java
public record CourseDetailResponse(
    Long id, String title, String description,
    int price, int capacity, int currentCount, int remainingCount,
    CourseStatus status, LocalDateTime startDate, LocalDateTime endDate
) {
    public static CourseDetailResponse from(Course c) {
        return new CourseDetailResponse(
            c.getId(), c.getTitle(), c.getDescription(),
            c.getPrice(), c.getCapacity(), c.getCurrentCount(),
            c.getCapacity() - c.getCurrentCount(),
            c.getStatus(), c.getStartDate(), c.getEndDate()
        );
    }
}

// CourseListResponse.java
public record CourseListResponse(
    Long id, String title, int price,
    int capacity, int currentCount, int remainingCount, CourseStatus status
) {
    public static CourseListResponse from(Course c) {
        return new CourseListResponse(
            c.getId(), c.getTitle(), c.getPrice(),
            c.getCapacity(), c.getCurrentCount(),
            c.getCapacity() - c.getCurrentCount(), c.getStatus()
        );
    }
}
```

---

## 9. UseCase 구현

### CreateCourseUseCase.java

```java
@Component
@RequiredArgsConstructor
public class CreateCourseUseCase {

    private final CourseService courseService;

    public CourseDetailResponse execute(CreateCourseRequest request) {
        // 날짜 검증은 Course 생성자 내부에서 수행
        Course course = Course.builder()
            .title(request.title())
            .description(request.description())
            .price(request.price())
            .capacity(request.capacity())
            .startDate(request.startDate())
            .endDate(request.endDate())
            .build();
        return CourseDetailResponse.from(courseService.create(course));
    }
}
```

### UpdateCourseStatusUseCase.java

```java
@Component
@RequiredArgsConstructor
public class UpdateCourseStatusUseCase {

    private final CourseService courseService;
    private final Cache<Long, CourseCapacityInfo> courseCapacityCache;
    private final EnrollmentSemaphoreManager semaphoreManager;

    public CourseDetailResponse execute(Long courseId, UpdateCourseStatusRequest request) {
        Course updated = courseService.updateStatus(courseId, request.status());

        // 캐시 무효화 — status 변경이 사전 검증 캐시에 즉시 반영되어야 함
        courseCapacityCache.invalidate(courseId);

        // CLOSED 전환 시 Semaphore 정리 (메모리 누수 방지)
        if (request.status() == CourseStatus.CLOSED) {
            semaphoreManager.removeSemaphore(courseId);
        }

        return CourseDetailResponse.from(updated);
    }
}
```

### GetCourseListUseCase.java

```java
@Component
@RequiredArgsConstructor
public class GetCourseListUseCase {

    private final CourseService courseService;

    // idx_course_status 인덱스 활용 (status 파라미터가 있을 때)
    public List<CourseListResponse> execute(CourseStatus status) {
        return courseService.findAll(status).stream()
            .map(CourseListResponse::from)
            .toList();
    }
}
```

### GetCourseDetailUseCase.java

```java
@Component
@RequiredArgsConstructor
public class GetCourseDetailUseCase {

    private final CourseService courseService;

    public CourseDetailResponse execute(Long courseId) {
        return CourseDetailResponse.from(courseService.findById(courseId));
    }
}
```

---

## 10. CourseController

```java
@RestController
@RequestMapping("/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CreateCourseUseCase createCourseUseCase;
    private final UpdateCourseStatusUseCase updateCourseStatusUseCase;
    private final GetCourseListUseCase getCourseListUseCase;
    private final GetCourseDetailUseCase getCourseDetailUseCase;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseDetailResponse createCourse(@RequestBody CreateCourseRequest request) {
        return createCourseUseCase.execute(request);
    }

    @PatchMapping("/{courseId}/status")
    public CourseDetailResponse updateStatus(
        @PathVariable Long courseId,
        @RequestBody UpdateCourseStatusRequest request
    ) {
        return updateCourseStatusUseCase.execute(courseId, request);
    }

    @GetMapping
    public List<CourseListResponse> getCourseList(
        @RequestParam(required = false) CourseStatus status
    ) {
        return getCourseListUseCase.execute(status);
    }

    @GetMapping("/{courseId}")
    public CourseDetailResponse getCourseDetail(@PathVariable Long courseId) {
        return getCourseDetailUseCase.execute(courseId);
    }
}
```

---

## 완료 체크리스트

- [ ] CourseStatus enum (canTransitionTo 포함)
- [ ] Course entity (5개 도메인 메서드 + null 방어 날짜 검증)
- [ ] CourseRepository interface (findByIdWithLock 포함)
- [ ] CourseJpaRepository (@Lock + @QueryHints 3000ms)
- [ ] CourseService (readOnly 트랜잭션 분리)
- [ ] CreateCourseUseCase
- [ ] UpdateCourseStatusUseCase (캐시 무효화 + Semaphore 정리)
- [ ] GetCourseListUseCase / GetCourseDetailUseCase
- [ ] Request/Response DTO (record)
- [ ] CourseController
- [ ] Member 최소 구현
