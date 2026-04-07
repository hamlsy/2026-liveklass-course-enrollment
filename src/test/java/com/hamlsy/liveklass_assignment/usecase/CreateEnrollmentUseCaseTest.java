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
}
