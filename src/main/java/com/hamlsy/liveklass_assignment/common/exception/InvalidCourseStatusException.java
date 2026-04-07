package com.hamlsy.liveklass_assignment.common.exception;

public class InvalidCourseStatusException extends BusinessException {
    public InvalidCourseStatusException() { super(ErrorCode.INVALID_COURSE_STATUS); }
    public InvalidCourseStatusException(ErrorCode code) { super(code); }
}
