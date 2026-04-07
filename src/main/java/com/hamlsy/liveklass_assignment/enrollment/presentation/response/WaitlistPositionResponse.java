package com.hamlsy.liveklass_assignment.enrollment.presentation.response;

public record WaitlistPositionResponse(
    Long courseId,
    Long userId,
    long position   // 1-based. 1 = 다음 승격 대상
) {}
