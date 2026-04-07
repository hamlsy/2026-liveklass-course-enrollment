package com.hamlsy.liveklass_assignment.common.exception;

public class AlreadyEnrolledException extends BusinessException {
    public AlreadyEnrolledException() { super(ErrorCode.ALREADY_ENROLLED); }
}
