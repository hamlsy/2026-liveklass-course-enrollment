package com.hamlsy.liveklass_assignment.course.application.usecase;

import com.hamlsy.liveklass_assignment.course.application.service.CourseService;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.presentation.request.CreateCourseRequest;
import com.hamlsy.liveklass_assignment.course.presentation.response.CourseDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CreateCourseUseCase {

    private final CourseService courseService;

    public CourseDetailResponse execute(CreateCourseRequest request) {
        Course course = Course.builder()
            .creatorId(request.creatorId())
            .title(request.title())
            .description(request.description())
            .price(request.price())
            .capacity(request.capacity())
            .startDate(request.startDate())
            .endDate(request.endDate())
            .build();
        return CourseDetailResponse.from(courseService.create(course));
    }
}
