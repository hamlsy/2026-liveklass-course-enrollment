package com.hamlsy.liveklass_assignment.common.exception;

public class CourseNotFoundException extends BusinessException {
    public CourseNotFoundException() { super(ErrorCode.COURSE_NOT_FOUND); }
}
