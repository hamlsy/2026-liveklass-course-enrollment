package com.hamlsy.liveklass_assignment.common.exception;

public class UnauthorizedCreatorException extends BusinessException {
    public UnauthorizedCreatorException() {
        super(ErrorCode.UNAUTHORIZED_CREATOR);
    }
}
