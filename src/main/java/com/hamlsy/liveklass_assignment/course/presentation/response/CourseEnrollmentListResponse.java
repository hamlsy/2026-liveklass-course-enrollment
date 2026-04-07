package com.hamlsy.liveklass_assignment.course.presentation.response;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;

public record CourseEnrollmentListResponse(
    Long courseId,
    String courseTitle,
    long totalElements,
    int totalPages,
    int page,
    int size,
    boolean hasNext,
    List<EnrollmentItem> enrollments
) {

    public record EnrollmentItem(
        Long enrollmentId,
        Long userId,
        EnrollmentStatus status,
        LocalDateTime enrolledAt,
        LocalDateTime confirmedAt
    ) {
        public static EnrollmentItem from(Enrollment e) {
            return new EnrollmentItem(
                e.getId(),
                e.getUserId(),
                e.getStatus(),
                e.getEnrolledAt(),
                e.getConfirmedAt()
            );
        }
    }

    public static CourseEnrollmentListResponse of(Long courseId, String courseTitle,
                                                   Page<Enrollment> page) {
        List<EnrollmentItem> items = page.getContent().stream()
            .map(EnrollmentItem::from)
            .toList();
        return new CourseEnrollmentListResponse(
            courseId,
            courseTitle,
            page.getTotalElements(),
            page.getTotalPages(),
            page.getNumber(),
            page.getSize(),
            page.hasNext(),
            items
        );
    }
}
