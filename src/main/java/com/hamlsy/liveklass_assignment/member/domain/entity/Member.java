package com.hamlsy.liveklass_assignment.member.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberRole role;

    @Builder
    public Member(String username, MemberRole role) {
        this.username = username;
        this.role = (role != null) ? role : MemberRole.STUDENT;
    }

    public boolean isCreator() {
        return this.role == MemberRole.CREATOR;
    }
}
