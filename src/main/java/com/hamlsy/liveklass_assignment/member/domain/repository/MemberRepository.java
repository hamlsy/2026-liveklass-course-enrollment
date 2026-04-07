package com.hamlsy.liveklass_assignment.member.domain.repository;

import com.hamlsy.liveklass_assignment.member.domain.entity.Member;

import java.util.Optional;

public interface MemberRepository {
    Member save(Member member);
    Optional<Member> findById(Long id);
}
