package com.hamlsy.liveklass_assignment.common.exception;

public class AlreadyInWaitlistException extends BusinessException {
    public AlreadyInWaitlistException() {
        super(ErrorCode.ALREADY_IN_WAITLIST);
    }
}
