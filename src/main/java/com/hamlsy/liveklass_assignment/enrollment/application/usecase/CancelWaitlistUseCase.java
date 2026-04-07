package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.hamlsy.liveklass_assignment.enrollment.application.service.WaitlistService;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.WaitlistResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CancelWaitlistUseCase {

    private final WaitlistService waitlistService;

    public WaitlistResponse execute(Long waitlistId, Long userId) {
        return WaitlistResponse.from(waitlistService.cancelWaitlist(waitlistId, userId));
    }
}
