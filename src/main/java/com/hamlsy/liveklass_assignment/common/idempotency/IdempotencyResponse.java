package com.hamlsy.liveklass_assignment.common.idempotency;

import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;

/**
 * 멱등성 캐시 저장 단위
 * enrollmentId: 이미 처리된 Enrollment ID
 * response: 클라이언트에 반환할 직렬화된 응답
 */
public record IdempotencyResponse(
    Long enrollmentId,
    EnrollmentResponse response
) {}
