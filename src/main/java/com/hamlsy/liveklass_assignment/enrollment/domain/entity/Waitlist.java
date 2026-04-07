package com.hamlsy.liveklass_assignment.enrollment.domain.entity;

import com.hamlsy.liveklass_assignment.course.domain.entity.Course;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "waitlist",
    indexes = {
        @Index(name = "idx_waitlist_course_status", columnList = "course_id, status"),
        @Index(name = "idx_waitlist_user_course",   columnList = "user_id, course_id")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Waitlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaitlistStatus status;

    @Column(nullable = false)
    private LocalDateTime waitlistedAt;

    @Builder
    public Waitlist(Long userId, Course course) {
        this.userId       = userId;
        this.course       = course;
        this.status       = WaitlistStatus.WAITING;
        this.waitlistedAt = LocalDateTime.now();
    }

    // ── 도메인 메서드 ─────────────────────────────────────────────

    /**
     * 대기열에서 수강 신청으로 승격.
     * 상태를 PROMOTED로 변경 — Enrollment 생성은 서비스 레이어에서 처리.
     */
    public void promote() {
        this.status = WaitlistStatus.PROMOTED;
    }

    /**
     * 대기 취소.
     * WAITING → CANCELLED. CANCELLED이면 멱등 no-op.
     * PROMOTED는 이 메서드로 취소 불가 (caller에서 사전 검증).
     */
    public boolean cancel() {
        if (this.status == WaitlistStatus.CANCELLED) {
            return false;  // 이미 취소됨 (멱등)
        }
        this.status = WaitlistStatus.CANCELLED;
        return true;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}
