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
