package com.hamlsy.liveklass_assignment.enrollment.domain.service;

import com.hamlsy.liveklass_assignment.common.exception.AlreadyEnrolledException;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.EnrollmentStatus;
import com.hamlsy.liveklass_assignment.enrollment.domain.repository.EnrollmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EnrollmentDomainService {

    private final EnrollmentRepository enrollmentRepository;

    /**
     * 중복 신청 검증
     * PENDING 또는 CONFIRMED 상태의 신청이 이미 존재하면 AlreadyEnrolledException.
     * CANCELLED는 포함하지 않음 — 취소 후 재신청은 허용.
     */
    public void validateNoDuplicateEnrollment(Long userId, Long courseId) {
        boolean exists = enrollmentRepository.existsByUserIdAndCourseIdAndStatusIn(
            userId, courseId,
            List.of(EnrollmentStatus.PENDING, EnrollmentStatus.CONFIRMED)
        );
        if (exists) {
            throw new AlreadyEnrolledException();
        }
    }
}
