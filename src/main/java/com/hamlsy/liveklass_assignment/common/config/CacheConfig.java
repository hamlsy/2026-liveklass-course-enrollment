package com.hamlsy.liveklass_assignment.common.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hamlsy.liveklass_assignment.common.concurrency.CourseCapacityInfo;
import com.hamlsy.liveklass_assignment.common.idempotency.IdempotencyResponse;
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
}
