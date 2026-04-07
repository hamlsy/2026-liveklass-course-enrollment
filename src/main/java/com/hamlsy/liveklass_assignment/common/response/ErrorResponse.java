package com.hamlsy.liveklass_assignment.common.response;

import com.hamlsy.liveklass_assignment.common.exception.ErrorCode;

public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage());
    }
}
