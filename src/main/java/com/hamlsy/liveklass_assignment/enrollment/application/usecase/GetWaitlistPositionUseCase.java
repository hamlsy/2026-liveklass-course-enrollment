package com.hamlsy.liveklass_assignment.enrollment.application.usecase;

import com.github.benmanes.caffeine.cache.Cache;
import com.hamlsy.liveklass_assignment.enrollment.application.service.WaitlistService;
import com.hamlsy.liveklass_assignment.enrollment.presentation.response.WaitlistPositionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 순번 조회마다 COUNT 쿼리가 실행되므로 TTL 2초 캐시로 DB 타격을 차단.
 * 캐시 키: "courseId:userId" 문자열 조합.
 */
@Component
@RequiredArgsConstructor
public class GetWaitlistPositionUseCase {

    private final WaitlistService waitlistService;
    private final Cache<String, Long> waitlistPositionCache;

    public WaitlistPositionResponse execute(Long userId, Long courseId) {
        String cacheKey = courseId + ":" + userId;
        Long cached = waitlistPositionCache.getIfPresent(cacheKey);
        if (cached != null) {
            return new WaitlistPositionResponse(courseId, userId, cached);
        }

        long position = waitlistService.getWaitlistPosition(userId, courseId);
        waitlistPositionCache.put(cacheKey, position);
        return new WaitlistPositionResponse(courseId, userId, position);
    }
}
