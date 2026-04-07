package com.hamlsy.liveklass_assignment.common.concurrency;

import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;

import java.time.LocalDateTime;

public record CourseCapacityInfo(
    CourseStatus status,
    int capacity,
    int currentCount,
    LocalDateTime startDate,
    LocalDateTime endDate
) {
    public boolean isLikelyFull() { return currentCount >= capacity; }

    public boolean isNotOpen() { return status != CourseStatus.OPEN; }

    public boolean isOutOfPeriod() {
        LocalDateTime now = LocalDateTime.now();
        return now.isBefore(startDate) || now.isAfter(endDate);
    }

    public static CourseCapacityInfo from(Course course) {
        return new CourseCapacityInfo(
            course.getStatus(), course.getCapacity(), course.getCurrentCount(),
            course.getStartDate(), course.getEndDate()
        );
    }
}
