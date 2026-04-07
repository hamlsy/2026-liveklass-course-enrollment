package com.hamlsy.liveklass_assignment.course.presentation;

import com.hamlsy.liveklass_assignment.course.application.usecase.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.presentation.request.CreateCourseRequest;
import com.hamlsy.liveklass_assignment.course.presentation.request.UpdateCourseStatusRequest;
import com.hamlsy.liveklass_assignment.course.presentation.response.CourseDetailResponse;
import com.hamlsy.liveklass_assignment.course.presentation.response.CourseListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CreateCourseUseCase createCourseUseCase;
    private final UpdateCourseStatusUseCase updateCourseStatusUseCase;
    private final GetCourseListUseCase getCourseListUseCase;
    private final GetCourseDetailUseCase getCourseDetailUseCase;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseDetailResponse createCourse(@RequestBody CreateCourseRequest request) {
        return createCourseUseCase.execute(request);
    }

    @PatchMapping("/{courseId}/status")
    public CourseDetailResponse updateStatus(
        @PathVariable Long courseId,
        @RequestBody UpdateCourseStatusRequest request
    ) {
        return updateCourseStatusUseCase.execute(courseId, request);
    }

    @GetMapping
    public List<CourseListResponse> getCourseList(
        @RequestParam(required = false) CourseStatus status
    ) {
        return getCourseListUseCase.execute(status);
    }

    @GetMapping("/{courseId}")
    public CourseDetailResponse getCourseDetail(@PathVariable Long courseId) {
        return getCourseDetailUseCase.execute(courseId);
    }
}
