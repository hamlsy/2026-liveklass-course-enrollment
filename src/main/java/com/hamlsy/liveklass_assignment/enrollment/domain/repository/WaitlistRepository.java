package com.hamlsy.liveklass_assignment.enrollment.domain.repository;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Waitlist;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.WaitlistStatus;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WaitlistRepository {

    Waitlist save(Waitlist waitlist);

    Optional<Waitlist> findById(Long id);

    /** 중복 대기 검증 */
    boolean existsByUserIdAndCourseIdAndStatus(Long userId, Long courseId, WaitlistStatus status);

    /**
     * 승격 대상 조회 — WAITING 상태 중 가장 오래된 1건.
     * waitlistedAt ASC 정렬 → FIFO 보장.
     * idx_waitlist_course_status 인덱스 활용.
     */
    Optional<Waitlist> findFirstByCourseIdAndStatusOrderByWaitlistedAtAsc(
        Long courseId, WaitlistStatus status
    );

    /**
     * 대기 순번 계산.
     * 내 waitlistedAt보다 앞선 WAITING 항목 수 + 1 = 현재 순번.
     */
    long countByCourseIdAndStatusAndWaitlistedAtBefore(
        Long courseId, WaitlistStatus status, LocalDateTime waitlistedAt
    );

    /** 유저 대기 항목 조회 (본인 확인 / 순번 조회용) */
    Optional<Waitlist> findByUserIdAndCourseIdAndStatus(
        Long userId, Long courseId, WaitlistStatus status
    );
}
