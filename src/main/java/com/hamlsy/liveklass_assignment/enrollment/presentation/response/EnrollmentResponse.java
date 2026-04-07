package com.hamlsy.liveklass_assignment.enrollment.presentation.response;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;

import java.time.LocalDateTime;

public record EnrollmentResponse(
    Long enrollmentId,
    Long userId,
    Long courseId,
    String courseTitle,
    int price,
    EnrollmentStatus status,
    LocalDateTime enrolledAt,
    LocalDateTime confirmedAt
) {
    public static EnrollmentResponse from(Enrollment enrollment) {
        return new EnrollmentResponse(
            enrollment.getId(),
            enrollment.getUserId(),
            enrollment.getCourse().getId(),
            enrollment.getCourse().getTitle(),  // JOIN FETCH 로딩됨 — 추가 쿼리 없음
            enrollment.getCourse().getPrice(),
            enrollment.getStatus(),
            enrollment.getEnrolledAt(),
            enrollment.getConfirmedAt()
        );
    }
}
