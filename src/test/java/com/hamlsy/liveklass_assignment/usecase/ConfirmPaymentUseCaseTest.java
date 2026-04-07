package com.hamlsy.liveklass_assignment.usecase;

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
