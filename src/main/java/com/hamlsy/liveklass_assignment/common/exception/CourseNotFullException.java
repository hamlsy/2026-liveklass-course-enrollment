package com.hamlsy.liveklass_assignment.common.exception;

public class CourseNotFullException extends BusinessException {
    public CourseNotFullException() {
        super(ErrorCode.COURSE_NOT_FULL);
    }
}
