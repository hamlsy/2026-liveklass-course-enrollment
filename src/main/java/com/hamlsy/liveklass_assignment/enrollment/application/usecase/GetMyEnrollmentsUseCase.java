package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.hamlsy.liveklass_assignment.common.response.PageResponse;
import com.hamlsy.liveklass_assignment.enrollment.application.service.EnrollmentService;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Enrollment;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GetMyEnrollmentsUseCase {

    private final EnrollmentService enrollmentService;

    /**
     * 내 수강 내역 조회 (페이지네이션)
     *
     * @param userId   조회 대상 유저 ID
     * @param pageable 페이지 정보 (page, size, sort) — 기본값: size=10, sort=enrolledAt DESC
     * @return PageResponse<EnrollmentResponse> — 페이지 메타데이터 포함
     */
    public PageResponse<EnrollmentResponse> execute(Long userId, Pageable pageable) {
        Page<Enrollment> page = enrollmentService.getMyEnrollmentsPage(userId, pageable);
        Page<EnrollmentResponse> mapped = page.map(EnrollmentResponse::from);
        return PageResponse.from(mapped);
    }
}
