package com.hamlsy.liveklass_assignment.common.exception;

public class CancellationPeriodExpiredException extends BusinessException {
    public CancellationPeriodExpiredException() { super(ErrorCode.CANCELLATION_PERIOD_EXPIRED); }
}
