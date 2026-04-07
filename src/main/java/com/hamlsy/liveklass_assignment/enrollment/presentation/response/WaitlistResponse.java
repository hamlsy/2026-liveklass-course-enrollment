package com.hamlsy.liveklass_assignment.enrollment.presentation.response;

import com.hamlsy.liveklass_assignment.enrollment.domain.entity.Waitlist;
import com.hamlsy.liveklass_assignment.enrollment.domain.entity.WaitlistStatus;

import java.time.LocalDateTime;

public record WaitlistResponse(
    Long waitlistId,
    Long userId,
    Long courseId,
    String courseTitle,
    WaitlistStatus status,
    LocalDateTime waitlistedAt
) {
    public static WaitlistResponse from(Waitlist waitlist) {
        return new WaitlistResponse(
            waitlist.getId(),
            waitlist.getUserId(),
            waitlist.getCourse().getId(),
            waitlist.getCourse().getTitle(),
            waitlist.getStatus(),
            waitlist.getWaitlistedAt()
        );
    }
}
