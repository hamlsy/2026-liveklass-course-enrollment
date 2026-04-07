package com.hamlsy.liveklass_assignment.course.application.usecase;

import com.hamlsy.liveklass_assignment.course.application.service.CourseService;
import com.hamlsy.liveklass_assignment.course.presentation.response.CourseDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetCourseDetailUseCase {

    private final CourseService courseService;

    public CourseDetailResponse execute(Long courseId) {
        return CourseDetailResponse.from(courseService.findById(courseId));
    }
}
