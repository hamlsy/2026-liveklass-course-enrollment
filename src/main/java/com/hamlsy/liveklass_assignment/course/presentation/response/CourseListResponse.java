package com.hamlsy.liveklass_assignment.course.presentation.response;

import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;

public record CourseListResponse(
    Long id,
    String title,
    int price,
    int capacity,
    int currentCount,
    int remainingCount,
    CourseStatus status
) {
    public static CourseListResponse from(Course c) {
        return new CourseListResponse(
            c.getId(), c.getTitle(), c.getPrice(),
            c.getCapacity(), c.getCurrentCount(),
            c.getCapacity() - c.getCurrentCount(), c.getStatus()
        );
    }
}
