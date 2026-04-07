package com.hamlsy.liveklass_assignment.enrollment.domain.repository;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;

import java.util.List;
import java.util.Optional;

public interface EnrollmentRepository {

    Enrollment save(Enrollment enrollment);

    Optional<Enrollment> findById(Long id);

    /**
     * 중복 신청 검증
     * idx_enrollment_user_course_status 인덱스 활용 (Covering Index)
     */
    boolean existsByUserIdAndCourseIdAndStatusIn(
        Long userId, Long courseId, List<EnrollmentStatus> statuses
    );

    /**
     * 내 수강 내역 조회 (N+1 방지)
     * JOIN FETCH로 course를 단일 쿼리로 로딩
     */
    List<Enrollment> findAllByUserIdWithCourse(Long userId);

    /**
     * 취소 처리용 — enrollment + course 를 함께 로딩
     * cancelEnrollment에서 enrollment와 course 모두 필요하므로
     * JOIN FETCH로 단일 쿼리 처리 (암묵적 LAZY 로딩 방지)
     */
    Optional<Enrollment> findByIdWithCourse(Long enrollmentId);
}
