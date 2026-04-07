package com.hamlsy.liveklass_assignment.enrollment.domain.repository;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * 강의별 수강생 목록 페이지 조회 (크리에이터 전용)
     * CANCELLED 제외: PENDING / CONFIRMED 수강생만 조회.
     * JOIN FETCH 없음 — e.course 데이터를 응답 DTO에서 사용하지 않음.
     */
    Page<Enrollment> findPageByCourseIdAndStatusIn(
        Long courseId, List<EnrollmentStatus> statuses, Pageable pageable
    );

    /**
     * 내 수강 내역 페이지네이션 조회 (N+1 방지)
     * JOIN FETCH + Pageable 조합 (ToOne → 행 증가 없음, 안전).
     * countQuery 분리로 COUNT 쿼리에서 불필요한 JOIN 제거.
     * 인덱스: idx_enrollment_user_enrolled_at (user_id, enrolled_at)
     */
    Page<Enrollment> findPageByUserIdWithCourse(Long userId, Pageable pageable);
}
