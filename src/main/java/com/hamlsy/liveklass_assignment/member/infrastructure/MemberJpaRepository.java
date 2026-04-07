package com.hamlsy.liveklass_assignment.member.infrastructure;

import com.hamlsy.liveklass_assignment.member.domain.entity.Member;
import com.hamlsy.liveklass_assignment.member.domain.repository.MemberRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberJpaRepository extends JpaRepository<Member, Long>, MemberRepository {}
