package com.hamlsy.liveklass_assignment.enrollment.domain.entity;

public enum EnrollmentStatus {
    PENDING,      // 신청 완료, 결제 대기
    CONFIRMED,    // 결제 완료
    CANCELLED;    // 취소됨

    public boolean isPending()     { return this == PENDING; }
    public boolean isConfirmed()   { return this == CONFIRMED; }
    public boolean isCancelled()   { return this == CANCELLED; }

    /** 취소 가능 상태: CONFIRMED 만 */
    public boolean isCancellable() { return this == CONFIRMED; }
}
