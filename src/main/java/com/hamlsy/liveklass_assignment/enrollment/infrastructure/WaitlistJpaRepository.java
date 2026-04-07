package com.hamlsy.liveklass_assignment.enrollment.infrastructure;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Waitlist;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.WaitlistStatus;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.WaitlistRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface WaitlistJpaRepository extends JpaRepository<Waitlist, Long>, WaitlistRepository {

    boolean existsByUserIdAndCourseIdAndStatus(Long userId, Long courseId, WaitlistStatus status);

    Optional<Waitlist> findFirstByCourseIdAndStatusOrderByWaitlistedAtAsc(
        Long courseId, WaitlistStatus status
    );

    long countByCourseIdAndStatusAndWaitlistedAtBefore(
        Long courseId, WaitlistStatus status, LocalDateTime waitlistedAt
    );

    Optional<Waitlist> findByUserIdAndCourseIdAndStatus(
        Long userId, Long courseId, WaitlistStatus status
    );
}
