package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.hamlsy.liveklass_assignment.enrollment.application.service.EnrollmentService;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CancelEnrollmentUseCase {

    private final EnrollmentService enrollmentService;

    /**
     * 수강 취소
     * [멱등성] 이미 CANCELLED 상태이면 예외 없이 현재 상태 반환.
     * currentCount 재감소 없음 (EnrollmentService에서 boolean 반환값으로 제어).
     *
     * @param userId 요청자 ID — 실제 환경에서는 SecurityContext에서 추출
     */
    public EnrollmentResponse execute(Long enrollmentId, Long userId) {
        return EnrollmentResponse.from(enrollmentService.cancelEnrollment(enrollmentId, userId));
    }
}
