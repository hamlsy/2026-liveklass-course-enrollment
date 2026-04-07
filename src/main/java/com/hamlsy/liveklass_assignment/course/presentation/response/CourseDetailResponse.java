package com.hamlsy.liveklass_assignment.course.presentation.response;

import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;

import java.time.LocalDateTime;

public record CourseDetailResponse(
    Long id,
    String title,
    String description,
    int price,
    int capacity,
    int currentCount,
    int remainingCount,
    CourseStatus status,
    LocalDateTime startDate,
    LocalDateTime endDate
) {
    public static CourseDetailResponse from(Course c) {
        return new CourseDetailResponse(
            c.getId(), c.getTitle(), c.getDescription(),
            c.getPrice(), c.getCapacity(), c.getCurrentCount(),
            c.getCapacity() - c.getCurrentCount(),
            c.getStatus(), c.getStartDate(), c.getEndDate()
        );
    }
}
