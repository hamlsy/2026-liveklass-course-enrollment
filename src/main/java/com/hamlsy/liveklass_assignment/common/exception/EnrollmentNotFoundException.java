package com.hamlsy.liveklass_assignment.common.exception;

public class EnrollmentNotFoundException extends BusinessException {
    public EnrollmentNotFoundException() { super(ErrorCode.ENROLLMENT_NOT_FOUND); }
}
