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
