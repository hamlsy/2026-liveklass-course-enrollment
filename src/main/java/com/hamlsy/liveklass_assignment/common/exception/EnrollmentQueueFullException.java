package com.hamlsy.liveklass_assignment.common.exception;

public class EnrollmentQueueFullException extends BusinessException {
    public EnrollmentQueueFullException() { super(ErrorCode.ENROLLMENT_QUEUE_FULL); }
}
