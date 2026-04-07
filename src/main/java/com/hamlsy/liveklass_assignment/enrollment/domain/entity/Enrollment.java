package com.hamlsy.liveklass_assignment.enrollment.domain.entity;

import com.hamlsy.liveklass_assignment.common.exception.BusinessException;
import com.hamlsy.liveklass_assignment.common.exception.CancellationPeriodExpiredException;
import com.hamlsy.liveklass_assignment.common.exception.ErrorCode;
import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "enrollment",
    indexes = {
        // 내 수강 내역 조회: WHERE user_id = ?
        @Index(name = "idx_enrollment_user_id", columnList = "user_id"),

        // 강의별 신청 조회: WHERE course_id = ?
        @Index(name = "idx_enrollment_course_id", columnList = "course_id"),

        // 중복 신청 검증: WHERE user_id = ? AND course_id = ? AND status IN (...)
        // Covering Index — 인덱스만으로 EXISTS 처리 (테이블 Row 접근 없음)
        @Index(name = "idx_enrollment_user_course_status",
               columnList = "user_id, course_id, status")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * userId는 Member FK 대신 Long으로 보관.
     * → 조회 시 Member JOIN 불필요, N+1 원천 차단.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EnrollmentStatus status;

    @Column(nullable = false)
    private LocalDateTime enrolledAt;

    private LocalDateTime confirmedAt;

    @Builder
    public Enrollment(Long userId, Course course) {
        this.userId     = userId;
        this.course     = course;
        this.status     = EnrollmentStatus.PENDING;
        this.enrolledAt = LocalDateTime.now();
    }

    // ── 도메인 메서드 ──────────────────────────────────────────────

    /**
     * 결제 확정: PENDING → CONFIRMED
     *
     * [멱등성] 이미 CONFIRMED 상태이면 아무 동작 없이 반환.
     * 클라이언트가 네트워크 오류로 confirm 요청을 재시도할 때 안전하게 처리.
     */
    public void confirm() {
        if (this.status.isConfirmed()) {
            return;  // 멱등: 이미 처리됨, 예외 없이 반환
        }
        if (!this.status.isPending()) {
            // CANCELLED 상태에서 confirm 시도 → 오류
            throw new BusinessException(ErrorCode.INVALID_ENROLLMENT_STATUS);
        }
        this.status      = EnrollmentStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    /**
     * 수강 취소
     * 1. 소유자 검증
     * 2. 이미 CANCELLED 면 멱등 반환 (재시도 안전)
     * 3. CONFIRMED 상태인지 검증
     * 4. 결제일(confirmedAt) 기준 7일 이내인지 검증
     * 5. CANCELLED 로 변경
     *
     * [멱등성] 이미 CANCELLED 상태이면 예외 없이 반환.
     * 단, 소유자 검증은 CANCELLED 이후에도 수행 (타인이 재시도하는 경우 방지).
     *
     * @return true = 실제 취소 처리됨, false = 이미 취소된 상태 (멱등 응답)
     */
    public boolean cancel(Long requestUserId) {
        validateOwnership(requestUserId);

        if (this.status.isCancelled()) {
            return false;  // 멱등: 이미 취소됨
        }

        validateCancellable();
        validateCancellationPeriod();
        this.status = EnrollmentStatus.CANCELLED;
        return true;  // 실제 취소 처리됨
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    // ── private ───────────────────────────────────────────────────

    private void validateOwnership(Long requestUserId) {
        if (!isOwnedBy(requestUserId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_ENROLLMENT);
        }
    }

    private void validateCancellable() {
        if (!this.status.isCancellable()) {
            throw new BusinessException(ErrorCode.INVALID_ENROLLMENT_STATUS);
        }
    }

    private void validateCancellationPeriod() {
        if (confirmedAt == null) return;
        if (LocalDateTime.now().isAfter(confirmedAt.plusDays(7))) {
            throw new CancellationPeriodExpiredException();
        }
    }
}
