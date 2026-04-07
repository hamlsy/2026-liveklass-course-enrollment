package com.hamlsy.liveklass_assignment.course.application.usecase;

import com.hamlsy.liveklass_assignment.course.application.service.CourseService;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.presentation.response.CourseListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class GetCourseListUseCase {

    private final CourseService courseService;

    // idx_course_status 인덱스 활용 (status 파라미터가 있을 때)
    public List<CourseListResponse> execute(CourseStatus status) {
        return courseService.findAll(status).stream()
            .map(CourseListResponse::from)
            .toList();
    }
}
