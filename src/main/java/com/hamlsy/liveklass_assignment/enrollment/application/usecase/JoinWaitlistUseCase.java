package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.github.benmanes.caffeine.cache.Cache;
import com.hamlsy.liveklass_assignment.common.concurrency.CourseCapacityInfo;
import com.hamlsy.liveklass_assignment.common.concurrency.EnrollmentSemaphoreManager;
import com.hamlsy.liveklass_assignment.common.exception.ErrorCode;
import com.hamlsy.liveklass_assignment.common.exception.InvalidCourseStatusException;
import com.hamlsy.liveklass_assignment.enrollment.application.service.WaitlistService;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Waitlist;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.WaitlistResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 대기열 등록 UseCase.
 *
 * [방어 1] Caffeine 사전 검증 (DB 미접근)
 *   - 캐시 hit 시 OPEN 아님 / 기간 외 조건을 즉시 거절.
 *   - isLikelyFull() 체크는 생략: 대기열은 정원 초과 상황에서 등록하므로
 *     캐시가 full이어도 실제로는 자리가 생겼을 수 있음 (invalidate 지연 가능성).
 *     정원 초과 여부의 정합성 검증은 WaitlistService에서 DB 기준으로 처리.
 *
 * [방어 2] Semaphore 스로틀링 (DB Connection Pool 보호)
 *   - 인기 강의 마감 직후 대기 등록 요청이 몰릴 때 무제한 DB 진입 방지.
 *   - createEnrollment와 동일한 per-course Semaphore 공유.
 *   - 트랜잭션 외부에서 acquire → 내부 작업 완료 후 release.
 */
@Component
@RequiredArgsConstructor
public class JoinWaitlistUseCase {

    private final WaitlistService waitlistService;
    private final EnrollmentSemaphoreManager semaphoreManager;
    private final Cache<Long, CourseCapacityInfo> courseCapacityCache;

    public WaitlistResponse execute(Long userId, Long courseId) {
        // ── [방어 1] Caffeine 사전 검증 ──────────────────────────────
        CourseCapacityInfo cached = courseCapacityCache.getIfPresent(courseId);
        if (cached != null) {
            if (cached.isNotOpen()) {
                throw new InvalidCourseStatusException();
            }
            if (cached.isOutOfPeriod()) {
                throw new InvalidCourseStatusException(ErrorCode.INVALID_ENROLLMENT_PERIOD);
            }
        }

        // ── [방어 2] Semaphore 획득 (트랜잭션 외부) ──────────────────
        semaphoreManager.acquire(courseId);
        try {
            Waitlist waitlist = waitlistService.joinWaitlist(userId, courseId);
            return WaitlistResponse.from(waitlist);
        } finally {
            semaphoreManager.release(courseId);
        }
    }
}
