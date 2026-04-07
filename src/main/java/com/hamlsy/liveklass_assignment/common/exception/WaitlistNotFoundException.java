package com.hamlsy.liveklass_assignment.common.exception;

public class WaitlistNotFoundException extends BusinessException {
    public WaitlistNotFoundException() {
        super(ErrorCode.WAITLIST_NOT_FOUND);
    }
}
