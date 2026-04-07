package com.hamlsy.liveklass_assignment.course.presentation.request;

import java.time.LocalDateTime;

public record CreateCourseRequest(
    Long creatorId,
    String title,
    String description,
    int price,
    int capacity,
    LocalDateTime startDate,
    LocalDateTime endDate
) {}
