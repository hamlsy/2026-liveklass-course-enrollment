package com.hamlsy.liveklass_assignment.enrollment.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.hamlsy.liveklass_assignment.common.concurrency.CourseCapacityInfo;
import com.hamlsy.liveklass_assignment.common.exception.*;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import com.hamlsy.liveklass_assignment.course.domain.repository.CourseRepository;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import com.hamlsy.liveklass_assignment.enrollment.domain.service.EnrollmentDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Service
@RequiredArgsConstructor
public class EnrollmentService {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final EnrollmentDomainService enrollmentDomainService;
    private final Cache<Long, CourseCapacityInfo> courseCapacityCache;
    private final WaitlistService waitlistService;

    /**
     * 수강 신청 (핵심 트랜잭션)
     *
     * Pessimistic Lock 범위:
     *   findByIdWithLock 호출 시점 ~ 트랜잭션 커밋까지 Row Lock 유지.
     *   동시에 같은 courseId로 진입한 스레드들이 순차 처리됨.
     *
     * 처리 순서:
     *   1. PESSIMISTIC_WRITE Lock으로 Course 조회
     *   2. 상태(OPEN) 검증
     *   3. 신청 기간 검증
     *   4. 중복 신청 검증 (DomainService)
     *   5. currentCount 증가 (원자적)
     *   6. Enrollment(PENDING) 저장
     *   7. 캐시 무효화
     */
    @Transactional
    public Enrollment createEnrollment(Long userId, Long courseId) {
        Course course = courseRepository.findByIdWithLock(courseId)
            .orElseThrow(CourseNotFoundException::new);

        course.validateOpen();
        course.validateEnrollmentPeriod();
        enrollmentDomainService.validateNoDuplicateEnrollment(userId, courseId);
        course.increaseCurrentCount();

        Enrollment enrollment = Enrollment.builder()
            .userId(userId)
            .course(course)
            .build();
        Enrollment saved = enrollmentRepository.save(enrollment);

        // currentCount 변경 → 사전 필터 캐시 무효화
        courseCapacityCache.invalidate(courseId);

        return saved;
    }

    /**
     * 결제 확정: PENDING → CONFIRMED
     *
     * [멱등성] confirm() 내부에서 이미 CONFIRMED면 아무 동작 없이 반환.
     * 클라이언트 재시도 시 동일 결과를 보장.
     */
    @Transactional
    public Enrollment confirmPayment(Long enrollmentId) {
        Enrollment enrollment = enrollmentRepository.findById(enrollmentId)
            .orElseThrow(EnrollmentNotFoundException::new);
        enrollment.confirm();  // 멱등: 이미 CONFIRMED면 no-op
        return enrollment;     // dirty checking으로 저장
    }

    /**
     * 수강 취소: CONFIRMED → CANCELLED + currentCount 감소
     *
     * [Race Condition 방지]
     * decreaseCurrentCount도 currentCount를 수정하므로 Pessimistic Lock 필요.
     * findByIdWithCourse로 enrollment + course를 단일 쿼리로 로딩한 뒤,
     * course에 별도로 Pessimistic Lock을 획득하여 currentCount 감소를 원자적으로 처리.
     *
     * [멱등성]
     * cancel() 반환값(boolean)으로 실제 취소 여부 판단.
     * false(이미 취소됨)면 currentCount 재감소 없이 현재 상태 반환.
     *
     * [N+1 방지]
     * findByIdWithCourse — enrollment + course를 JOIN FETCH로 단일 쿼리 로딩.
     */
    @Transactional
    public Enrollment cancelEnrollment(Long enrollmentId, Long userId) {
        // enrollment + course 단일 쿼리 로딩 (N+1 방지)
        Enrollment enrollment = enrollmentRepository.findByIdWithCourse(enrollmentId)
            .orElseThrow(EnrollmentNotFoundException::new);

        // 도메인 메서드: 소유자 검증 + 상태 검증 + 7일 검증 + CANCELLED 변경
        // 반환값: true = 실제 취소됨, false = 이미 취소된 상태 (멱등)
        boolean actuallyCancelled = enrollment.cancel(userId);

        if (actuallyCancelled) {
            Long courseId = enrollment.getCourse().getId();

            // Pessimistic Lock 획득 (findByIdWithCourse는 Lock 없이 로딩했으므로 별도 Lock 필요)
            Course lockedCourse = courseRepository.findByIdWithLock(courseId)
                .orElseThrow(CourseNotFoundException::new);

            // 대기열 승격 시도 (Lock 보유 중 동일 트랜잭션 내 실행 — Propagation.MANDATORY)
            // 승격 대상 있음: promote() + Enrollment 생성 + increaseCurrentCount()
            //   → currentCount -1(취소) +1(승격) = 순증감 없음, decreaseCurrentCount 불필요
            // 승격 대상 없음: decreaseCurrentCount()로 정원 반환
            boolean promoted = waitlistService.tryPromoteNextWaiting(courseId, lockedCourse);
            if (!promoted) {
                lockedCourse.decreaseCurrentCount();
            }

            // 캐시 무효화
            courseCapacityCache.invalidate(courseId);
        }

        return enrollment;
    }

    /**
     * 내 수강 내역 페이지네이션 조회
     * JOIN FETCH + Pageable (ToOne — 행 증가 없음, 안전)
     * 인덱스: idx_enrollment_user_enrolled_at (user_id, enrolled_at) → filesort 없음
     */
    @Transactional(readOnly = true)
    public Page<Enrollment> getMyEnrollmentsPage(Long userId, Pageable pageable) {
        return enrollmentRepository.findPageByUserIdWithCourse(userId, pageable);
    }
}
