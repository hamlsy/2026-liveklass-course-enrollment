package com.hamlsy.liveklass_assignment.common.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hamlsy.liveklass_assignment.common.concurrency.CourseCapacityInfo;
import com.hamlsy.liveklass_assignment.common.idempotency.IdempotencyResponse;
import com.hamlsy.liveklass_assignment.member.domain.entity.MemberRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /**
     * 수강 신청 사전 필터링용 캐시
     * TTL 5초: stale 오탐 최소화. 실제 정합성은 Pessimistic Lock이 보장.
     */
    @Bean
    public Cache<Long, CourseCapacityInfo> courseCapacityCache() {
        return Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 멱등성 키 → 응답 캐시
     * TTL 24시간: 클라이언트 재시도 윈도우
     */
    @Bean
    public Cache<String, IdempotencyResponse> idempotencyCache() {
        return Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();
    }

    /**
     * Member role 캐시 (per memberId)
     * TTL 60초: role은 거의 변하지 않는 정적 데이터.
     * 크리에이터가 반복 조회 시 Member SELECT 제거.
     */
    @Bean
    public Cache<Long, MemberRole> memberRoleCache() {
        return Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();
    }

    /**
     * 대기 순번 캐시 (per user-course)
     * TTL 2초: 순번 조회마다 발생하는 COUNT 쿼리 중복 차단.
     * 키: "courseId:userId" 문자열 조합.
     * 2초 stale은 사용자 경험에 무해하다.
     */
    @Bean
    public Cache<String, Long> waitlistPositionCache() {
        return Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(2, TimeUnit.SECONDS)
            .build();
    }
}
