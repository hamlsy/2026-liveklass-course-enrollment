package com.hamlsy.liveklass_assignment.enrollment.presentation;

import com.hamlsy.liveklass_assignment.enrollment.application.usecase.CancelWaitlistUseCase;
import com.hamlsy.liveklass_assignment.enrollment.application.usecase.GetWaitlistPositionUseCase;
import com.hamlsy.liveklass_assignment.enrollment.application.usecase.JoinWaitlistUseCase;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.WaitlistPositionResponse;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.WaitlistResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final JoinWaitlistUseCase joinWaitlistUseCase;
    private final CancelWaitlistUseCase cancelWaitlistUseCase;
    private final GetWaitlistPositionUseCase getWaitlistPositionUseCase;

    /** 대기열 등록 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WaitlistResponse joinWaitlist(
        @RequestParam Long userId,
        @RequestParam Long courseId
    ) {
        return joinWaitlistUseCase.execute(userId, courseId);
    }

    /** 대기 취소 */
    @PatchMapping("/{waitlistId}/cancel")
    public WaitlistResponse cancelWaitlist(
        @PathVariable Long waitlistId,
        @RequestParam Long userId
    ) {
        return cancelWaitlistUseCase.execute(waitlistId, userId);
    }

    /** 대기 순번 조회 */
    @GetMapping("/position")
    public WaitlistPositionResponse getPosition(
        @RequestParam Long userId,
        @RequestParam Long courseId
    ) {
        return getWaitlistPositionUseCase.execute(userId, courseId);
    }
}
