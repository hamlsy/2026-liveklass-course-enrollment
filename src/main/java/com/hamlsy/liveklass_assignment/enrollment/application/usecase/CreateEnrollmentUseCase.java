package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.github.benmanes.caffeine.cache.Cache;
import com.hamlsy.liveklass_assignment.common.concurrency.CourseCapacityInfo;
import com.hamlsy.liveklass_assignment.common.concurrency.EnrollmentSemaphoreManager;
import com.hamlsy.liveklass_assignment.common.exception.EnrollmentLimitExceededException;
import com.hamlsy.liveklass_assignment.common.exception.InvalidCourseStatusException;
import com.hamlsy.liveklass_assignment.common.exception.ErrorCode;
import com.hamlsy.liveklass_assignment.common.idempotency.IdempotencyInterceptor;
import com.hamlsy.liveklass_assignment.common.idempotency.IdempotencyResponse;
import com.hamlsy.liveklass_assignment.enrollment.application.service.EnrollmentService;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.presentation.request.CreateEnrollmentRequest;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
@RequiredArgsConstructor
public class CreateEnrollmentUseCase {

    private final EnrollmentService enrollmentService;
    private final EnrollmentSemaphoreManager semaphoreManager;
    private final Cache<Long, CourseCapacityInfo> courseCapacityCache;
    private final Cache<String, IdempotencyResponse> idempotencyCache;

    /**
     * 수강 신청 실행
     *
     * [방어 1] Caffeine Cache 사전 검증 (트랜잭션 외부, DB 미접근)
     *   - 캐시 hit 시: OPEN 아님 / 기간 외 / 정원 확실히 찬 경우 즉시 거절
     *   - 캐시 miss 시: 생략 → DB에서 정확하게 검증
     *   - 사전 필터 목적. 실제 정합성은 Lock이 보장.
     *
     * [방어 2] 강의별 Semaphore (트랜잭션 외부)
     *   - 강의당 동시 진입 스레드 MAX_CONCURRENT_PER_COURSE(=5)로 제한
     *   - 2초 내 미획득 시 503 반환
     *   - DB Connection Pool 고갈 방지
     *   - Semaphore는 반드시 트랜잭션 시작 전에 획득해야 함
     *     (트랜잭션 내부에 두면 Connection 점유 후 대기 → Pool 고갈 동일)
     *
     * [방어 3] Pessimistic Lock (트랜잭션 내부, EnrollmentService)
     *   - 실제 정합성 보장
     *   - lock.timeout = 3000ms
     *
     * [멱등성] Idempotency-Key 헤더 기반
     *   - 인터셉터(IdempotencyInterceptor)가 preHandle에서 캐시 hit 시 컨트롤러 진입 자체를 차단
     *   - 캐시 miss(첫 요청)이면 UseCase 실행 후 결과를 idempotencyCache에 저장
     */
    public EnrollmentResponse execute(Long userId, CreateEnrollmentRequest request) {
        Long courseId = request.courseId();

        // ── [방어 1] Caffeine 사전 검증 ──────────────────────────────
        CourseCapacityInfo cached = courseCapacityCache.getIfPresent(courseId);
        if (cached != null) {
            if (cached.isNotOpen()) {
                throw new InvalidCourseStatusException();
            }
            if (cached.isOutOfPeriod()) {
                throw new InvalidCourseStatusException(ErrorCode.INVALID_ENROLLMENT_PERIOD);
            }
            if (cached.isLikelyFull()) {
                throw new EnrollmentLimitExceededException();
            }
        }

        // ── [방어 2] Semaphore 획득 (트랜잭션 외부) ──────────────────
        semaphoreManager.acquire(courseId);
        try {
            // ── [방어 3] 실제 트랜잭션 (Pessimistic Lock) ────────────
            Enrollment enrollment = enrollmentService.createEnrollment(userId, courseId);
            EnrollmentResponse response = EnrollmentResponse.from(enrollment);

            // ── [멱등성] 결과를 Idempotency Key와 함께 캐시에 저장 ───
            saveIdempotencyResult(enrollment.getId(), response);

            return response;

        } finally {
            semaphoreManager.release(courseId);  // 반드시 해제
        }
    }

    /**
     * 현재 요청의 Idempotency-Key가 있으면 결과를 캐시에 저장.
     * RequestContextHolder로 현재 요청의 attribute에서 키를 꺼낸다.
     */
    private void saveIdempotencyResult(Long enrollmentId, EnrollmentResponse response) {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;

            HttpServletRequest request = attrs.getRequest();
            String key = (String) request.getAttribute(IdempotencyInterceptor.IDEMPOTENCY_KEY_ATTR);
            if (key != null && !key.isBlank()) {
                idempotencyCache.put(key, new IdempotencyResponse(enrollmentId, response));
            }
        } catch (Exception e) {
            // 캐시 저장 실패는 치명적이지 않음 — 다음 재시도에서 중복 처리됨
            // 중복 신청 검증(DB)이 두 번째 방어선 역할을 함
        }
    }
}
