package com.hamlsy.liveklass_assignment.enrollment.presentation;

import com.hamlsy.liveklass_assignment.enrollment.application.usecase.*;
import com.hamlsy.liveklass_assignment.enrollment.presentation.request.CreateEnrollmentRequest;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/enrollments")
@RequiredArgsConstructor
public class EnrollmentController {

    private final CreateEnrollmentUseCase createEnrollmentUseCase;
    private final ConfirmPaymentUseCase confirmPaymentUseCase;
    private final CancelEnrollmentUseCase cancelEnrollmentUseCase;
    private final GetMyEnrollmentsUseCase getMyEnrollmentsUseCase;

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

    @GetMapping("/me")
    public List<EnrollmentResponse> getMyEnrollments(@RequestParam Long userId) {
        return getMyEnrollmentsUseCase.execute(userId);
    }
}
