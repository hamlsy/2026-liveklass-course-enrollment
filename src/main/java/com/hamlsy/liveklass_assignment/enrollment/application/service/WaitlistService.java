package com.hamlsy.liveklass_assignment.enrollment.application.service;

import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.*;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.WaitlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WaitlistService {

    private final CourseRepository courseRepository;
    private final WaitlistRepository waitlistRepository;
    private final EnrollmentRepository enrollmentRepository;

    /**
     * 대기열 등록.
     *
     * 호출 조건: 정원이 꽉 찬 OPEN 강의.
     * 중복 대기 검증 후 Waitlist(WAITING) 생성.
     *
     * ※ Caffeine 사전 필터링은 JoinWaitlistUseCase에서 처리
     *   (isNotOpen / isOutOfPeriod 캐시 hit 시 DB 미접근 즉시 거절).
     *   정합성 보장은 이 메서드의 DB 검증이 담당.
     */
    @Transactional
    public Waitlist joinWaitlist(Long userId, Long courseId) {
        Course course = courseRepository.findById(courseId)
            .orElseThrow(CourseNotFoundException::new);

        course.validateOpen();
        course.validateEnrollmentPeriod();

        // 정원 초과 여부 검증: 아직 자리가 남아있으면 수강 신청으로 유도
        if (!course.isFull()) {
            throw new CourseNotFullException();
        }

        // 중복 대기 검증
        if (waitlistRepository.existsByUserIdAndCourseIdAndStatus(
                userId, courseId, WaitlistStatus.WAITING)) {
            throw new AlreadyInWaitlistException();
        }

        // 이미 수강 중(PENDING/CONFIRMED)인 경우 대기 불가
        if (enrollmentRepository.existsByUserIdAndCourseIdAndStatusIn(
                userId, courseId,
                List.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED))) {
            throw new AlreadyEnrolledException();
        }

        Waitlist waitlist = Waitlist.builder()
            .userId(userId)
            .course(course)
            .build();

        return waitlistRepository.save(waitlist);
    }

    /**
     * 대기열 취소.
     *
     * WAITING → CANCELLED 전환.
     * PROMOTED: 이미 Enrollment가 생성된 상태이므로 취소 불가.
     * CANCELLED: 멱등 처리 (no-op, 현재 상태 반환).
     */
    @Transactional
    public Waitlist cancelWaitlist(Long waitlistId, Long userId) {
        Waitlist waitlist = waitlistRepository.findById(waitlistId)
            .orElseThrow(WaitlistNotFoundException::new);

        if (!waitlist.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.WAITLIST_UNAUTHORIZED);
        }

        // PROMOTED 상태: Enrollment가 이미 생성되었으므로 대기열 취소 불가
        if (waitlist.getStatus() == WaitlistStatus.PROMOTED) {
            throw new BusinessException(ErrorCode.WAITLIST_ALREADY_PROMOTED);
        }

        // WAITING → CANCELLED (CANCELLED이면 멱등 no-op)
        waitlist.cancel();
        return waitlist;
    }

    /**
     * 대기 순번 조회.
     *
     * countByCourseIdAndStatusAndWaitlistedAtBefore + 1 = 현재 순번.
     * 예) 앞에 2명 WAITING → 내 순번 = 3
     *
     * [DB 부하] 순번 조회마다 COUNT 쿼리가 실행된다.
     * GetWaitlistPositionUseCase에서 waitlistPositionCache (TTL 2초)로 결과를 캐싱한다.
     */
    @Transactional(readOnly = true)
    public long getWaitlistPosition(Long userId, Long courseId) {
        Waitlist waitlist = waitlistRepository
            .findByUserIdAndCourseIdAndStatus(userId, courseId, WaitlistStatus.WAITING)
            .orElseThrow(WaitlistNotFoundException::new);

        return waitlistRepository.countByCourseIdAndStatusAndWaitlistedAtBefore(
            courseId, WaitlistStatus.WAITING, waitlist.getWaitlistedAt()
        ) + 1;
    }

    /**
     * 취소 발생 시 대기열 첫 번째 유저 자동 승격.
     *
     * 반환값: 승격 대상이 있으면 true, 없으면 false.
     * caller(EnrollmentService)는 false일 때만 decreaseCurrentCount() 호출.
     *
     * Propagation.MANDATORY: 반드시 기존 트랜잭션(Pessimistic Lock 보유) 내에서 실행.
     * 독립 트랜잭션으로 분리하면 Lock 해제 후 두 취소가 동일 Waitlist 항목을 중복 승격할 수 있다.
     *
     * 승격 흐름:
     *   1. WAITING 중 waitlistedAt ASC 첫 번째 조회 (FIFO)
     *   2. Waitlist.promote() → PROMOTED
     *   3. Enrollment(PENDING) 신규 생성
     *   4. course.increaseCurrentCount() (Lock 이미 보유)
     *   → currentCount: cancel -1 후 promote +1 = 순증감 없음
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryPromoteNextWaiting(Long courseId, Course lockedCourse) {
        Optional<Waitlist> candidate = waitlistRepository
            .findFirstByCourseIdAndStatusOrderByWaitlistedAtAsc(courseId, WaitlistStatus.WAITING);

        if (candidate.isEmpty()) {
            return false;
        }

        Waitlist waitlist = candidate.get();
        waitlist.promote();

        Enrollment promoted = Enrollment.builder()
            .userId(waitlist.getUserId())
            .course(lockedCourse)
            .build();
        enrollmentRepository.save(promoted);

        lockedCourse.increaseCurrentCount();
        return true;
    }
}
