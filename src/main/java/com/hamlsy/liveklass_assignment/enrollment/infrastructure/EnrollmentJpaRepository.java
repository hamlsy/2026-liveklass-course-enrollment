package com.hamlsy.liveklass_assignment.enrollment.infrastructure;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * 강의별 수강생 목록 페이지 조회 (크리에이터 전용)
     * JOIN FETCH 없음: EnrollmentItem은 e.course 데이터를 사용하지 않음.
     * courseTitle은 별도 로딩된 Course 객체에서 가져오므로 불필요한 JOIN 제거.
     * countQuery도 동일하게 단순 COUNT로 처리.
     */
    @Query(
        value = """
            SELECT e FROM Enrollment e
            WHERE e.course.id = :courseId
            AND e.status IN :statuses
            """,
        countQuery = """
            SELECT COUNT(e) FROM Enrollment e
            WHERE e.course.id = :courseId
            AND e.status IN :statuses
            """
    )
    Page<Enrollment> findPageByCourseIdAndStatusIn(
        @Param("courseId") Long courseId,
        @Param("statuses") List<EnrollmentStatus> statuses,
        Pageable pageable
    );

    /**
     * 내 수강 내역 페이지네이션 조회 (N+1 방지)
     *
     * JOIN FETCH + Pageable (ToOne) — HHH90003004 경고 없음.
     * Enrollment → Course는 @ManyToOne이므로 행 증가 없이 안전.
     *
     * 인덱스 활용: idx_enrollment_user_enrolled_at (user_id, enrolled_at)
     *   → WHERE user_id = ? ORDER BY enrolled_at DESC LIMIT ? OFFSET ?
     *   → Index Range Scan, filesort 없음.
     *
     * countQuery: JOIN 제거 — COUNT에 course 데이터 불필요.
     */
    @Query(
        value = """
            SELECT e FROM Enrollment e
            JOIN FETCH e.course
            WHERE e.userId = :userId
            """,
        countQuery = """
            SELECT COUNT(e) FROM Enrollment e
            WHERE e.userId = :userId
            """
    )
    Page<Enrollment> findPageByUserIdWithCourse(
        @Param("userId") Long userId,
        Pageable pageable
    );
}
