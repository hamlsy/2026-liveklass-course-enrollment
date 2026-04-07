package com.hamlsy.liveklass_assignment.enrollment.presentation;

import com.hamlsy.liveklass_assignment.common.response.PageResponse;
import com.hamlsy.liveklass_assignment.course.presentation.response.CourseEnrollmentListResponse;
import com.hamlsy.liveklass_assignment.enrollment.application.usecase.*;
import com.hamlsy.liveklass_assignment.enrollment.presentation.request.CreateEnrollmentRequest;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/enrollments")
@RequiredArgsConstructor
public class EnrollmentController {

    private final CreateEnrollmentUseCase createEnrollmentUseCase;
    private final ConfirmPaymentUseCase confirmPaymentUseCase;
    private final CancelEnrollmentUseCase cancelEnrollmentUseCase;
    private final GetMyEnrollmentsUseCase getMyEnrollmentsUseCase;
    private final GetCourseEnrollmentsUseCase getCourseEnrollmentsUseCase;

    /**
     * 수강 신청
     * Idempotency-Key 헤더 지원 (선택):
     *   - 헤더 포함 시: 동일 키 재시도 → 캐시된 응답 반환
     *   - 헤더 없으면: 정상 처리 (단, 중복 신청은 AlreadyEnrolledException으로 차단)
     *
     * userId: 실제 환경에서는 SecurityContext에서 추출.
     *         과제에서는 요청 파라미터로 전달.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse createEnrollment(
        @RequestParam Long userId,
        @RequestBody CreateEnrollmentRequest request
    ) {
        return createEnrollmentUseCase.execute(userId, request);
    }

    @PatchMapping("/{enrollmentId}/confirm")
    public EnrollmentResponse confirmPayment(@PathVariable Long enrollmentId) {
        return confirmPaymentUseCase.execute(enrollmentId);
    }

    @PatchMapping("/{enrollmentId}/cancel")
    public EnrollmentResponse cancelEnrollment(
        @PathVariable Long enrollmentId,
        @RequestParam Long userId
    ) {
        return cancelEnrollmentUseCase.execute(enrollmentId, userId);
    }

    /**
     * 내 수강 내역 조회 (페이지네이션)
     *
     * 기본값: page=0, size=10, sort=enrolledAt DESC (최신 신청 순)
     * max-page-size: 100 (application.yaml)
     *
     * 예시:
     *   GET /enrollments/me?userId=1
     *   GET /enrollments/me?userId=1&page=0&size=5
     *   GET /enrollments/me?userId=1&page=1&size=10&sort=enrolledAt,ASC
     */
    @GetMapping("/me")
    public PageResponse<EnrollmentResponse> getMyEnrollments(
        @RequestParam Long userId,
        @PageableDefault(size = 10, sort = "enrolledAt", direction = Sort.Direction.DESC)
        Pageable pageable
    ) {
        return getMyEnrollmentsUseCase.execute(userId, pageable);
    }

    /**
     * 강의별 수강생 목록 조회 (크리에이터 전용, 페이지네이션)
     *
     * 기본값: page=0, size=20, sort=enrolledAt ASC
     * 예시:
     *   GET /enrollments/courses/1/enrollments?creatorId=10
     *   GET /enrollments/courses/1/enrollments?creatorId=10&page=1&size=10
     *
     * @param creatorId 요청자 ID (실 서비스: SecurityContext에서 추출)
     */
    @GetMapping("/courses/{courseId}/enrollments")
    public CourseEnrollmentListResponse getCourseEnrollments(
        @PathVariable Long courseId,
        @RequestParam Long creatorId,
        @PageableDefault(size = 20, sort = "enrolledAt", direction = Sort.Direction.ASC)
        Pageable pageable
    ) {
        return getCourseEnrollmentsUseCase.execute(courseId, creatorId, pageable);
    }
}
