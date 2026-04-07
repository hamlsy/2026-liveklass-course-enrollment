# Phase 5: 테스트 구현

## 목표
- 동시성 통합 테스트 (Race Condition 검증)
- 도메인 단위 테스트 (Entity 비즈니스 로직 + 멱등성)
- UseCase 통합 테스트 (멱등성 포함)
- 테스트 격리 전략 (@Transactional vs @DirtiesContext)

---

## 1. 테스트 격리 전략

| 테스트 종류 | 격리 방법 | 이유 |
|---|---|---|
| 도메인 단위 테스트 (CourseTest, EnrollmentTest) | Spring 없음 (순수 JUnit) | Entity는 Spring 의존 없음. 가장 빠름. |
| UseCase 통합 테스트 | `@SpringBootTest` + `@Transactional` | 트랜잭션 롤백으로 DB 초기화. 컨텍스트 재시작 없음. |
| 동시성 테스트 | `@SpringBootTest` + `@DirtiesContext` | 멀티스레드는 `@Transactional` 롤백 불가. 컨텍스트 재시작 필수. |

> **`@DirtiesContext`를 동시성 테스트에만 적용하는 이유**
> UseCase 테스트에 `@DirtiesContext`를 붙이면 매 테스트마다 Spring ApplicationContext를 재시작한다.
> 컨텍스트 초기화는 수 초가 걸리므로 테스트 수가 늘어날수록 빌드 시간이 급격히 증가한다.
> `@Transactional` 롤백으로 격리 가능한 테스트에는 `@DirtiesContext`를 절대 붙이지 않는다.

---

## 2. 테스트 구조

```
src/test/java/com/hamlsy/liveklass_assignment/
├── domain/
│   ├── CourseTest.java                      ← 순수 단위 테스트 (Spring 없음)
│   └── EnrollmentTest.java                  ← 순수 단위 테스트 (Spring 없음, 멱등성 포함)
├── usecase/
│   ├── CreateEnrollmentUseCaseTest.java     ← 통합 테스트 (@Transactional 롤백)
│   ├── ConfirmPaymentUseCaseTest.java       ← 멱등성 검증
│   └── CancelEnrollmentUseCaseTest.java     ← 멱등성 + currentCount 복원 검증
└── concurrency/
    └── CreateEnrollmentConcurrencyTest.java ← @DirtiesContext, Race Condition 검증
```

---

## 3. 도메인 단위 테스트

### CourseTest.java

```java
package com.hamlsy.liveklass_assignment.domain;

import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class CourseTest {

    private Course openCourse;
    private Course draftCourse;

    @BeforeEach
    void setUp() {
        openCourse = Course.builder()
            .title("테스트 강의").description("설명").price(10000).capacity(30)
            .startDate(LocalDateTime.now().minusDays(1))
            .endDate(LocalDateTime.now().plusDays(30))
            .build();
        openCourse.changeStatus(CourseStatus.OPEN);

        draftCourse = Course.builder()
            .title("초안 강의").description("설명").price(5000).capacity(10)
            .startDate(LocalDateTime.now().plusDays(1))
            .endDate(LocalDateTime.now().plusDays(30))
            .build();
    }

    @Nested
    @DisplayName("validateOpen()")
    class ValidateOpen {

        @Test
        @DisplayName("OPEN 상태이면 예외 없음")
        void open_상태_검증_성공() {
            assertThatNoException().isThrownBy(openCourse::validateOpen);
        }

        @Test
        @DisplayName("DRAFT 상태이면 InvalidCourseStatusException")
        void draft_상태_검증_실패() {
            assertThatThrownBy(draftCourse::validateOpen)
                .isInstanceOf(InvalidCourseStatusException.class);
        }

        @Test
        @DisplayName("CLOSED 상태이면 InvalidCourseStatusException")
        void closed_상태_검증_실패() {
            openCourse.changeStatus(CourseStatus.CLOSED);
            assertThatThrownBy(openCourse::validateOpen)
                .isInstanceOf(InvalidCourseStatusException.class);
        }
    }

    @Nested
    @DisplayName("increaseCurrentCount()")
    class IncreaseCurrentCount {

        @Test
        @DisplayName("정원 미만이면 currentCount 1 증가")
        void 정원_미만_증가() {
            openCourse.increaseCurrentCount();
            assertThat(openCourse.getCurrentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("정원 딱 채운 직후 추가 신청 시 EnrollmentLimitExceededException")
        void 정원_초과_예외() {
            for (int i = 0; i < 30; i++) openCourse.increaseCurrentCount();
            assertThatThrownBy(openCourse::increaseCurrentCount)
                .isInstanceOf(EnrollmentLimitExceededException.class);
        }
    }

    @Nested
    @DisplayName("decreaseCurrentCount()")
    class DecreaseCurrentCount {

        @Test
        @DisplayName("정상 감소")
        void 정상_감소() {
            openCourse.increaseCurrentCount();
            openCourse.decreaseCurrentCount();
            assertThat(openCourse.getCurrentCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("이미 0이면 음수가 되지 않음 (방어 코드)")
        void 0이하_방어() {
            openCourse.decreaseCurrentCount(); // 이미 0
            assertThat(openCourse.getCurrentCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("changeStatus()")
    class ChangeStatus {

        @Test
        @DisplayName("DRAFT → OPEN 전이 성공")
        void draft_to_open() {
            draftCourse.changeStatus(CourseStatus.OPEN);
            assertThat(draftCourse.getStatus()).isEqualTo(CourseStatus.OPEN);
        }

        @Test
        @DisplayName("OPEN → CLOSED 전이 성공")
        void open_to_closed() {
            openCourse.changeStatus(CourseStatus.CLOSED);
            assertThat(openCourse.getStatus()).isEqualTo(CourseStatus.CLOSED);
        }

        @Test
        @DisplayName("CLOSED → OPEN 전이 불가 — InvalidCourseStatusException")
        void closed_to_open_불가() {
            openCourse.changeStatus(CourseStatus.CLOSED);
            assertThatThrownBy(() -> openCourse.changeStatus(CourseStatus.OPEN))
                .isInstanceOf(InvalidCourseStatusException.class);
        }

        @Test
        @DisplayName("DRAFT → CLOSED 직접 전이 불가")
        void draft_to_closed_불가() {
            assertThatThrownBy(() -> draftCourse.changeStatus(CourseStatus.CLOSED))
                .isInstanceOf(InvalidCourseStatusException.class);
        }
    }

    @Nested
    @DisplayName("Course 생성 날짜 검증")
    class DateValidation {

        @Test
        @DisplayName("종료일이 시작일과 동일하면 예외")
        void 종료일_동일_예외() {
            LocalDateTime same = LocalDateTime.now().plusDays(5);
            assertThatThrownBy(() -> Course.builder()
                .title("t").price(1000).capacity(10)
                .startDate(same).endDate(same).build()
            ).isInstanceOf(InvalidCourseStatusException.class);
        }

        @Test
        @DisplayName("종료일이 시작일 이전이면 예외")
        void 종료일_이전_예외() {
            assertThatThrownBy(() -> Course.builder()
                .title("t").price(1000).capacity(10)
                .startDate(LocalDateTime.now().plusDays(10))
                .endDate(LocalDateTime.now().plusDays(1))
                .build()
            ).isInstanceOf(InvalidCourseStatusException.class);
        }
    }
}
```

---

### EnrollmentTest.java

```java
package com.hamlsy.liveklass_assignment.domain;

import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnrollmentTest {

    private Course mockCourse;
    private Enrollment enrollment;

    @BeforeEach
    void setUp() {
        mockCourse = mock(Course.class);
        enrollment = Enrollment.builder().userId(1L).course(mockCourse).build();
    }

    @Nested
    @DisplayName("confirm() — 멱등성 포함")
    class Confirm {

        @Test
        @DisplayName("PENDING → CONFIRMED 성공, confirmedAt 기록")
        void pending_to_confirmed() {
            enrollment.confirm();
            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
            assertThat(enrollment.getConfirmedAt()).isNotNull();
        }

        @Test
        @DisplayName("[멱등성] 이미 CONFIRMED이면 예외 없이 반환")
        void 이미_confirmed_멱등() {
            enrollment.confirm();
            // 두 번째 호출 — 예외 없이 반환되어야 함
            assertThatNoException().isThrownBy(enrollment::confirm);
            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CONFIRMED);
        }

        @Test
        @DisplayName("CANCELLED 상태에서 confirm 시도 → INVALID_ENROLLMENT_STATUS")
        void cancelled_상태_confirm_예외() {
            enrollment.confirm();
            enrollment.cancel(1L);  // CONFIRMED → CANCELLED
            assertThatThrownBy(enrollment::confirm)
                .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("cancel() — 멱등성 포함")
    class Cancel {

        @BeforeEach
        void confirmFirst() {
            enrollment.confirm();  // PENDING → CONFIRMED
        }

        @Test
        @DisplayName("본인 7일 이내 취소 성공 — true 반환")
        void 본인_취소_성공() {
            boolean result = enrollment.cancel(1L);
            assertThat(result).isTrue();
            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        }

        @Test
        @DisplayName("[멱등성] 이미 CANCELLED이면 false 반환, 예외 없음")
        void 이미_cancelled_멱등() {
            enrollment.cancel(1L);  // 첫 번째 취소
            boolean result = enrollment.cancel(1L);  // 두 번째 취소 (재시도)
            assertThat(result).isFalse();  // 멱등: false 반환
            assertThat(enrollment.getStatus()).isEqualTo(EnrollmentStatus.CANCELLED);
        }

        @Test
        @DisplayName("타인이 취소 시 UNAUTHORIZED_ENROLLMENT 예외")
        void 타인_취소_예외() {
            assertThatThrownBy(() -> enrollment.cancel(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED_ENROLLMENT));
        }

        @Test
        @DisplayName("PENDING 상태에서 취소 시 INVALID_ENROLLMENT_STATUS 예외")
        void pending_상태_취소_예외() {
            Enrollment pending = Enrollment.builder().userId(1L).course(mockCourse).build();
            assertThatThrownBy(() -> pending.cancel(1L))
                .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("결제 후 7일 초과 시 CancellationPeriodExpiredException")
        void 7일_초과_취소_예외() {
            // confirmedAt을 8일 전으로 조작
            ReflectionTestUtils.setField(enrollment, "confirmedAt",
                LocalDateTime.now().minusDays(8));

            assertThatThrownBy(() -> enrollment.cancel(1L))
                .isInstanceOf(CancellationPeriodExpiredException.class);
        }

        @Test
        @DisplayName("[멱등성] 이미 CANCELLED인 경우 타인도 cancel 시도 시 소유자 검증 실패")
        void 이미_cancelled_타인_재시도_예외() {
            enrollment.cancel(1L);  // 본인이 취소
            // 타인이 재시도 → 소유자 검증에서 막힘 (멱등 반환 전에 소유자 검증 수행)
            assertThatThrownBy(() -> enrollment.cancel(99L))
                .isInstanceOf(BusinessException.class);
        }
    }
}
```

---

## 4. UseCase 통합 테스트

### CreateEnrollmentUseCaseTest.java

```java
package com.hamlsy.liveklass_assignment.usecase;

import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.enrollment.application.usecase.CreateEnrollmentUseCase;
import com.hamlsy.liveklass_assignment.enrollment.presentation.request.CreateEnrollmentRequest;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional  // 각 테스트 후 롤백 — @DirtiesContext 불필요
class CreateEnrollmentUseCaseTest {

    @Autowired private CreateEnrollmentUseCase createEnrollmentUseCase;
    @Autowired private CourseRepository courseRepository;

    private Long openCourseId;
    private Long draftCourseId;
    private Long expiredCourseId;

    @BeforeEach
    void setUp() {
        Course openCourse = Course.builder()
            .title("OPEN 강의").description("desc").price(10000).capacity(5)
            .startDate(LocalDateTime.now().minusDays(1))
            .endDate(LocalDateTime.now().plusDays(30))
            .build();
        openCourse.changeStatus(CourseStatus.OPEN);
        openCourseId = courseRepository.save(openCourse).getId();

        Course draftCourse = Course.builder()
            .title("DRAFT 강의").description("desc").price(5000).capacity(5)
            .startDate(LocalDateTime.now().plusDays(1))
            .endDate(LocalDateTime.now().plusDays(30))
            .build();
        draftCourseId = courseRepository.save(draftCourse).getId();

        // 기간 만료 강의 (신청 기간 종료)
        Course expiredCourse = Course.builder()
            .title("기간 만료 강의").description("desc").price(5000).capacity(10)
            .startDate(LocalDateTime.now().minusDays(30))
            .endDate(LocalDateTime.now().minusDays(1))  // 어제 종료
            .build();
        expiredCourse.changeStatus(CourseStatus.OPEN);
        expiredCourseId = courseRepository.save(expiredCourse).getId();
    }

    @Test
    @DisplayName("OPEN 강의 정상 신청 성공")
    void open_강의_신청_성공() {
        EnrollmentResponse response =
            createEnrollmentUseCase.execute(1L, new CreateEnrollmentRequest(openCourseId));
        assertThat(response).isNotNull();
        assertThat(response.courseId()).isEqualTo(openCourseId);
    }

    @Test
    @DisplayName("DRAFT 강의 신청 시 InvalidCourseStatusException")
    void draft_강의_신청_예외() {
        assertThatThrownBy(() ->
            createEnrollmentUseCase.execute(1L, new CreateEnrollmentRequest(draftCourseId))
        ).isInstanceOf(InvalidCourseStatusException.class);
    }

    @Test
    @DisplayName("신청 기간 종료 강의 신청 시 InvalidCourseStatusException")
    void 기간_만료_강의_신청_예외() {
        assertThatThrownBy(() ->
            createEnrollmentUseCase.execute(1L, new CreateEnrollmentRequest(expiredCourseId))
        ).isInstanceOf(InvalidCourseStatusException.class);
    }

    @Test
    @DisplayName("정원 초과 시 EnrollmentLimitExceededException")
    void 정원_초과_예외() {
        for (int i = 1; i <= 5; i++) {
            createEnrollmentUseCase.execute((long) i, new CreateEnrollmentRequest(openCourseId));
        }
        assertThatThrownBy(() ->
            createEnrollmentUseCase.execute(6L, new CreateEnrollmentRequest(openCourseId))
        ).isInstanceOf(EnrollmentLimitExceededException.class);
    }

    @Test
    @DisplayName("동일 사용자 중복 신청 시 AlreadyEnrolledException")
    void 중복_신청_예외() {
        createEnrollmentUseCase.execute(1L, new CreateEnrollmentRequest(openCourseId));
        assertThatThrownBy(() ->
            createEnrollmentUseCase.execute(1L, new CreateEnrollmentRequest(openCourseId))
        ).isInstanceOf(AlreadyEnrolledException.class);
    }

    @Test
    @DisplayName("취소 후 재신청 허용 (CANCELLED는 중복 검증에서 제외)")
    void 취소_후_재신청_허용() {
        // 이 시나리오는 CancelEnrollmentUseCaseTest에서 통합 검증
        // 여기서는 CANCELLED 상태가 중복 검증 대상에 포함되지 않음을 확인
        // (EnrollmentDomainService: PENDING, CONFIRMED 만 검사)
    }
}
```

---

### ConfirmPaymentUseCaseTest.java

```java
package com.hamlsy.liveklass_assignment.usecase;

import com.hamlsy.liveklass_assignment.common.exception.BusinessException;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.enrollment.application.usecase.ConfirmPaymentUseCase;
import com.hamlsy.liveklass_assignment.enrollment.application.usecase.CreateEnrollmentUseCase;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import com.hamlsy.liveklass_assignment.enrollment.presentation.request.CreateEnrollmentRequest;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class ConfirmPaymentUseCaseTest {

    @Autowired private CreateEnrollmentUseCase createEnrollmentUseCase;
    @Autowired private ConfirmPaymentUseCase confirmPaymentUseCase;
    @Autowired private CourseRepository courseRepository;

    private Long enrollmentId;

    @BeforeEach
    void setUp() {
        Course course = Course.builder()
            .title("결제 테스트").description("desc").price(10000).capacity(10)
            .startDate(LocalDateTime.now().minusDays(1))
            .endDate(LocalDateTime.now().plusDays(30))
            .build();
        course.changeStatus(CourseStatus.OPEN);
        Long courseId = courseRepository.save(course).getId();

        EnrollmentResponse enrollment =
            createEnrollmentUseCase.execute(1L, new CreateEnrollmentRequest(courseId));
        enrollmentId = enrollment.enrollmentId();
    }

    @Test
    @DisplayName("PENDING → CONFIRMED 성공")
    void confirm_성공() {
        EnrollmentResponse response = confirmPaymentUseCase.execute(enrollmentId);
        assertThat(response.status()).isEqualTo(EnrollmentStatus.CONFIRMED);
    }

    @Test
    @DisplayName("[멱등성] 이미 CONFIRMED이면 예외 없이 CONFIRMED 상태 반환")
    void 이미_confirmed_멱등() {
        confirmPaymentUseCase.execute(enrollmentId);  // 첫 번째 확정
        // 두 번째 호출 — 예외 없이 동일 상태 반환
        EnrollmentResponse response = confirmPaymentUseCase.execute(enrollmentId);
        assertThat(response.status()).isEqualTo(EnrollmentStatus.CONFIRMED);
    }
}
```

---

### CancelEnrollmentUseCaseTest.java

```java
package com.hamlsy.liveklass_assignment.usecase;

import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.enrollment.application.usecase.*;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import com.hamlsy.liveklass_assignment.enrollment.presentation.request.CreateEnrollmentRequest;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class CancelEnrollmentUseCaseTest {

    @Autowired private CreateEnrollmentUseCase createEnrollmentUseCase;
    @Autowired private ConfirmPaymentUseCase confirmPaymentUseCase;
    @Autowired private CancelEnrollmentUseCase cancelEnrollmentUseCase;
    @Autowired private CourseRepository courseRepository;

    private Long courseId;
    private Long enrollmentId;

    @BeforeEach
    void setUp() {
        Course course = Course.builder()
            .title("취소 테스트").description("desc").price(10000).capacity(10)
            .startDate(LocalDateTime.now().minusDays(1))
            .endDate(LocalDateTime.now().plusDays(30))
            .build();
        course.changeStatus(CourseStatus.OPEN);
        courseId = courseRepository.save(course).getId();

        EnrollmentResponse enrollment =
            createEnrollmentUseCase.execute(1L, new CreateEnrollmentRequest(courseId));
        enrollmentId = enrollment.enrollmentId();
        confirmPaymentUseCase.execute(enrollmentId);  // CONFIRMED 상태로 전환
    }

    @Test
    @DisplayName("CONFIRMED → CANCELLED 성공 + currentCount 복원")
    void 취소_성공_currentCount_복원() {
        cancelEnrollmentUseCase.execute(enrollmentId, 1L);

        Course result = courseRepository.findById(courseId).orElseThrow();
        assertThat(result.getCurrentCount())
            .as("취소 후 currentCount는 0으로 복원되어야 한다")
            .isEqualTo(0);
    }

    @Test
    @DisplayName("[멱등성] 이미 CANCELLED이면 예외 없이 CANCELLED 상태 반환, currentCount 재감소 없음")
    void 이미_cancelled_멱등_currentCount_불변() {
        cancelEnrollmentUseCase.execute(enrollmentId, 1L);  // 첫 번째 취소
        Course afterFirst = courseRepository.findById(courseId).orElseThrow();
        int countAfterFirst = afterFirst.getCurrentCount();  // 0

        // 두 번째 취소 (재시도) — 예외 없이 반환, currentCount 변화 없음
        EnrollmentResponse response = cancelEnrollmentUseCase.execute(enrollmentId, 1L);
        assertThat(response.status()).isEqualTo(EnrollmentStatus.CANCELLED);

        Course afterSecond = courseRepository.findById(courseId).orElseThrow();
        assertThat(afterSecond.getCurrentCount())
            .as("멱등 취소 시 currentCount가 재감소되면 안 된다")
            .isEqualTo(countAfterFirst);  // 동일한 값 유지
    }

    @Test
    @DisplayName("타인이 취소 시 UNAUTHORIZED_ENROLLMENT 예외")
    void 타인_취소_예외() {
        assertThatThrownBy(() -> cancelEnrollmentUseCase.execute(enrollmentId, 99L))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("취소 후 재신청 성공 (CANCELLED는 중복 검증 제외)")
    void 취소_후_재신청_성공() {
        cancelEnrollmentUseCase.execute(enrollmentId, 1L);

        // 재신청 — AlreadyEnrolledException이 발생하지 않아야 함
        EnrollmentResponse reEnrollment =
            createEnrollmentUseCase.execute(1L, new CreateEnrollmentRequest(courseId));
        assertThat(reEnrollment).isNotNull();
        assertThat(reEnrollment.status()).isEqualTo(EnrollmentStatus.PENDING);
    }
}
```

---

## 5. 동시성 통합 테스트

### CreateEnrollmentConcurrencyTest.java

```java
package com.hamlsy.liveklass_assignment.concurrency;

import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.enrollment.application.usecase.CreateEnrollmentUseCase;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import com.hamlsy.liveklass_assignment.enrollment.presentation.request.CreateEnrollmentRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/**
 * 동시성 테스트는 멀티스레드 특성상 @Transactional 롤백이 불가.
 * 각 테스트 후 @DirtiesContext로 컨텍스트 재시작하여 H2 초기화.
 *
 * 주의: 이 테스트는 컨텍스트 재시작 비용이 크므로 CI에서는 별도로 실행 권장.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CreateEnrollmentConcurrencyTest {

    @Autowired private CreateEnrollmentUseCase createEnrollmentUseCase;
    @Autowired private CourseRepository courseRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;

    private Long courseId;
    private static final int CAPACITY     = 30;
    private static final int THREAD_COUNT = 100;

    @BeforeEach
    void setUp() {
        Course course = Course.builder()
            .title("동시성 테스트 강의").description("test").price(10000).capacity(CAPACITY)
            .startDate(LocalDateTime.now().minusDays(1))
            .endDate(LocalDateTime.now().plusDays(30))
            .build();
        course.changeStatus(CourseStatus.OPEN);
        courseId = courseRepository.save(course).getId();
    }

    @Test
    @DisplayName("100명 동시 신청 → 정원(30)만 성공, currentCount 정합성 보장")
    void 동시_100명_신청시_정원_초과하지_않는다() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(THREAD_COUNT);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final long userId = i + 1L;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();  // 모든 스레드 준비 후 동시 출발
                    createEnrollmentUseCase.execute(userId, new CreateEnrollmentRequest(courseId));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();           // 모든 스레드 준비 완료 대기
        start.countDown();       // 동시 출발 신호
        done.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // 성공 건수 = 정원
        assertThat(successCount.get())
            .as("성공 건수는 정원(30)과 일치해야 한다")
            .isEqualTo(CAPACITY);

        // 실패 건수 = 전체 - 정원
        assertThat(failCount.get())
            .as("실패 건수는 70이어야 한다")
            .isEqualTo(THREAD_COUNT - CAPACITY);

        // DB currentCount = 정원 (정합성 검증)
        Course result = courseRepository.findById(courseId).orElseThrow();
        assertThat(result.getCurrentCount())
            .as("DB currentCount는 정원(30)과 일치해야 한다")
            .isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("동일 사용자 10회 동시 중복 신청 → 1건만 성공")
    void 동일_사용자_중복_신청시_1건만_성공한다() throws InterruptedException {
        final long SAME_USER_ID  = 999L;
        final int  ATTEMPT_COUNT = 10;

        ExecutorService executor = Executors.newFixedThreadPool(ATTEMPT_COUNT);
        CountDownLatch ready = new CountDownLatch(ATTEMPT_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(ATTEMPT_COUNT);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        for (int i = 0; i < ATTEMPT_COUNT; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    createEnrollmentUseCase.execute(SAME_USER_ID,
                        new CreateEnrollmentRequest(courseId));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get())
            .as("동일 사용자의 동시 중복 신청은 1건만 성공해야 한다")
            .isEqualTo(1);

        Course result = courseRepository.findById(courseId).orElseThrow();
        assertThat(result.getCurrentCount())
            .as("currentCount는 1이어야 한다")
            .isEqualTo(1);
    }
}
```

---

## 6. 테스트 실행 명령어

```bash
# 전체 테스트
./gradlew test

# 도메인 단위 테스트 (가장 빠름 — Spring 없음)
./gradlew test --tests "*.domain.*"

# UseCase 통합 테스트
./gradlew test --tests "*.usecase.*"

# 동시성 테스트만 (느림 — @DirtiesContext)
./gradlew test --tests "*.concurrency.*"

# 테스트 리포트
# build/reports/tests/test/index.html
```

---

## 7. 전체 테스트 시나리오 목록

| 테스트 클래스 | 시나리오 | 검증 항목 |
|---|---|---|
| `CourseTest` | OPEN/DRAFT/CLOSED 상태 검증 | 예외 발생 여부 |
| `CourseTest` | increaseCurrentCount 정상/초과 | currentCount 값, 예외 |
| `CourseTest` | decreaseCurrentCount 정상/0이하 방어 | currentCount 값 |
| `CourseTest` | changeStatus 4가지 전이 | status 값, 예외 |
| `CourseTest` | 날짜 검증 (동일/이전) | 생성 시 예외 |
| `EnrollmentTest` | confirm() PENDING→CONFIRMED | status, confirmedAt |
| `EnrollmentTest` | confirm() **멱등** — 이미 CONFIRMED | 예외 없음 |
| `EnrollmentTest` | confirm() CANCELLED 상태 | 예외 |
| `EnrollmentTest` | cancel() 본인 7일 이내 | true 반환, CANCELLED |
| `EnrollmentTest` | cancel() **멱등** — 이미 CANCELLED | false 반환, 예외 없음 |
| `EnrollmentTest` | cancel() 타인 요청 | UNAUTHORIZED 예외 |
| `EnrollmentTest` | cancel() 7일 초과 | CancellationPeriodExpiredException |
| `EnrollmentTest` | cancel() 이미 CANCELLED + 타인 재시도 | 소유자 예외 |
| `CreateEnrollmentUseCaseTest` | OPEN 강의 정상 신청 | response 반환 |
| `CreateEnrollmentUseCaseTest` | DRAFT 강의 신청 | InvalidCourseStatusException |
| `CreateEnrollmentUseCaseTest` | 기간 만료 강의 신청 | InvalidCourseStatusException |
| `CreateEnrollmentUseCaseTest` | 정원 초과 신청 | EnrollmentLimitExceededException |
| `CreateEnrollmentUseCaseTest` | 중복 신청 | AlreadyEnrolledException |
| `ConfirmPaymentUseCaseTest` | PENDING→CONFIRMED 성공 | CONFIRMED 상태 |
| `ConfirmPaymentUseCaseTest` | **멱등** — 이미 CONFIRMED 재시도 | 예외 없음, CONFIRMED |
| `CancelEnrollmentUseCaseTest` | CONFIRMED→CANCELLED + currentCount 복원 | CANCELLED, count==0 |
| `CancelEnrollmentUseCaseTest` | **멱등** — 이미 CANCELLED 재시도 | 예외 없음, count 불변 |
| `CancelEnrollmentUseCaseTest` | 타인 취소 | BusinessException |
| `CancelEnrollmentUseCaseTest` | 취소 후 재신청 허용 | PENDING 상태 반환 |
| `CreateEnrollmentConcurrencyTest` | 100명 동시 신청 (capacity=30) | success==30, count==30 |
| `CreateEnrollmentConcurrencyTest` | 동일 사용자 10회 동시 중복 신청 | success==1, count==1 |

---

## 완료 체크리스트

- [ ] CourseTest (Spring 없음, 7개 시나리오)
- [ ] EnrollmentTest (Spring 없음, 멱등성 시나리오 포함, 8개)
- [ ] CreateEnrollmentUseCaseTest (@Transactional 롤백, 5개)
- [ ] ConfirmPaymentUseCaseTest (멱등성 포함, 2개)
- [ ] CancelEnrollmentUseCaseTest (멱등성 + currentCount 복원 + 재신청, 4개)
- [ ] CreateEnrollmentConcurrencyTest (@DirtiesContext, 2개)
