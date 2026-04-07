package com.hamlsy.liveklass_assignment.course.application.usecase;

import com.github.benmanes.caffeine.cache.Cache;
import com.hamlsy.liveklass_assignment.common.concurrency.CourseCapacityInfo;
import com.hamlsy.liveklass_assignment.common.concurrency.EnrollmentSemaphoreManager;
import com.hamlsy.liveklass_assignment.course.application.service.CourseService;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.entity.CourseStatus;
import com.hamlsy.liveklass_assignment.course.presentation.request.UpdateCourseStatusRequest;
import com.hamlsy.liveklass_assignment.course.presentation.response.CourseDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UpdateCourseStatusUseCase {

    private final CourseService courseService;
    private final Cache<Long, CourseCapacityInfo> courseCapacityCache;
    private final EnrollmentSemaphoreManager semaphoreManager;

    public CourseDetailResponse execute(Long courseId, UpdateCourseStatusRequest request) {
        Course updated = courseService.updateStatus(courseId, request.status());

        // 캐시 무효화 — status 변경이 사전 검증 캐시에 즉시 반영되어야 함
        courseCapacityCache.invalidate(courseId);

        // CLOSED 전환 시 Semaphore 정리 (메모리 누수 방지)
        if (request.status() == CourseStatus.CLOSED) {
            semaphoreManager.removeSemaphore(courseId);
        }

        return CourseDetailResponse.from(updated);
    }
}
