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
