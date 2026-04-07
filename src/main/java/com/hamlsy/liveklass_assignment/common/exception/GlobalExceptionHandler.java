package com.hamlsy.liveklass_assignment.common.exception;

import com.hamlsy.liveklass_assignment.common.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        log.warn("BusinessException: {}", e.getMessage());
        return ResponseEntity
            .status(e.getErrorCode().getHttpStatus())
            .body(ErrorResponse.of(e.getErrorCode()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return ResponseEntity.internalServerError()
            .body(new ErrorResponse("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다."));
    }
}
