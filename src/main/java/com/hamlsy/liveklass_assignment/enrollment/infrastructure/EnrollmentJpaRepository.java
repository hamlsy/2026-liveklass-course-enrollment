package com.hamlsy.liveklass_assignment.enrollment.infrastructure;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EnrollmentJpaRepository
        extends JpaRepository<Enrollment, Long>, EnrollmentRepository {

    /**
     * 중복 신청 검증
     * Spring Data JPA 메서드 이름 쿼리 — idx_enrollment_user_course_status 활용
     */
    boolean existsByUserIdAndCourseIdAndStatusIn(
        Long userId, Long courseId, List<EnrollmentStatus> statuses
    );

    /**
     * N+1 방지: JOIN FETCH로 course를 함께 로딩
     *
     * 단순 findAllByUserId 사용 시 문제:
     *   SELECT * FROM enrollment WHERE user_id = ?   → 1번
     *   SELECT * FROM course WHERE id = ?            → N번 추가 (N+1 문제)
     *
     * JOIN FETCH 사용 시:
     *   SELECT e.*, c.* FROM enrollment e
     *   INNER JOIN course c ON e.course_id = c.id
     *   WHERE e.user_id = ?                          → 단일 쿼리로 해결
     */
    @Query("SELECT e FROM Enrollment e JOIN FETCH e.course WHERE e.userId = :userId")
    List<Enrollment> findAllByUserIdWithCourse(@Param("userId") Long userId);

    /**
     * 취소 처리용 — enrollment + course 단일 쿼리
     *
     * cancelEnrollment 흐름에서 enrollment.cancel() 후 course.decreaseCurrentCount() 호출.
     * course를 명시적 JOIN FETCH로 미리 로딩하여 암묵적 LAZY 쿼리 방지.
     */
    @Query("SELECT e FROM Enrollment e JOIN FETCH e.course WHERE e.id = :enrollmentId")
    Optional<Enrollment> findByIdWithCourse(@Param("enrollmentId") Long enrollmentId);
}
