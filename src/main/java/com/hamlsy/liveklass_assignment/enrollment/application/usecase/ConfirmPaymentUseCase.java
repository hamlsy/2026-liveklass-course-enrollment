package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.hamlsy.liveklass_assignment.enrollment.application.service.EnrollmentService;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConfirmPaymentUseCase {

    private final EnrollmentService enrollmentService;

    /**
     * 결제 확정
     * [멱등성] 이미 CONFIRMED 상태이면 예외 없이 현재 상태 반환.
     * 클라이언트가 응답을 받지 못해 재시도해도 안전하다.
     */
    public EnrollmentResponse execute(Long enrollmentId) {
        return EnrollmentResponse.from(enrollmentService.confirmPayment(enrollmentId));
    }
}
