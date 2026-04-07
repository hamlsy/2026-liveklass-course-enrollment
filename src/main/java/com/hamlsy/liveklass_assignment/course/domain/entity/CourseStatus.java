package com.hamlsy.liveklass_assignment.course.domain.entity;

public enum CourseStatus {
    DRAFT,
    OPEN,
    CLOSED;

    /**
     * 상태 전이 규칙:
     *   DRAFT  → OPEN   (모집 시작)
     *   OPEN   → CLOSED (마감)
     *   CLOSED → (불가) — 한번 닫힌 강의는 재오픈 불가
     */
    public boolean canTransitionTo(CourseStatus next) {
        return switch (this) {
            case DRAFT  -> next == OPEN;
            case OPEN   -> next == CLOSED;
            case CLOSED -> false;
        };
    }
}
