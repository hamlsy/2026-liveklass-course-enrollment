package com.hamlsy.liveklass_assignment.common.exception;

public class EnrollmentLimitExceededException extends BusinessException {
    public EnrollmentLimitExceededException() { super(ErrorCode.ENROLLMENT_LIMIT_EXCEEDED); }
}
