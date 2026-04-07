package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.hamlsy.liveklass_assignment.enrollment.application.service.EnrollmentService;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.EnrollmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class GetMyEnrollmentsUseCase {

    private final EnrollmentService enrollmentService;

    public List<EnrollmentResponse> execute(Long userId) {
        return enrollmentService.getMyEnrollments(userId).stream()
            .map(EnrollmentResponse::from)
            .toList();
    }
}
