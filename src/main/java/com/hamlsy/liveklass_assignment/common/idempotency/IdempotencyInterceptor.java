package com.hamlsy.liveklass_assignment.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * POST /enrollments 멱등성 처리 인터셉터
 *
 * 클라이언트는 요청 헤더에 Idempotency-Key (UUID)를 포함해야 한다.
 * 동일 키로 재요청 시 캐시된 응답을 그대로 반환 (DB 재처리 없음).
 *
 * 처리 흐름:
 *   1. 헤더에서 Idempotency-Key 추출
 *   2. 캐시 조회
 *      - Hit: 캐시된 응답을 201로 반환, 컨트롤러 실행 중단
 *      - Miss: 컨트롤러 실행 진행, 키를 request attribute에 저장
 *
 * 키가 없으면 정상 처리 (키는 선택 사항)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENCY_KEY_ATTR = "idempotencyKey";

    private final Cache<String, IdempotencyResponse> idempotencyCache;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String key = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        if (key == null || key.isBlank()) {
            return true;
        }

        IdempotencyResponse cached = idempotencyCache.getIfPresent(key);
        if (cached != null) {
            log.debug("Idempotency cache hit: key={}, enrollmentId={}", key, cached.enrollmentId());
            response.setStatus(HttpStatus.CREATED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(cached.response()));
            return false;
        }

        request.setAttribute(IDEMPOTENCY_KEY_ATTR, key);
        return true;
    }
}
