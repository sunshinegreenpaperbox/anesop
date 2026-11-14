package com.anesop.m4.api.staff;

import java.time.LocalDate;
import java.util.List;

/**
 * 授权判定结果（进程内集成用，M1 排程 / M5 麻醉记录）。
 *
 * <p>与契约 {@code AuthorizationCheckResult} 同义，但**不依赖 HTTP 上下文**：M1/M5 在完成
 * 自己的权限与数据范围校验后调用 {@link StaffAuthorizationService#evaluate} 即可拿到判定，
 * 不需要传调用者，也不需要跨进程调用。{@code reasonCodes} 为稳定原因码（见契约
 * {@code AuthorizationCheckReason.code}），{@code allowed == reasonCodes.isEmpty()}。
 *
 * <p>本结果只表达“授权范围是否允许”，**不表示该台麻醉在临床上适宜**（AGENTS H-06）。
 *
 * @param allowed         是否允许
 * @param reasonCodes     未通过原因码（全部返回；允许时为空列表）
 * @param level           命中的授权级别（无授权时为 null）
 * @param validUntil      命中授权的失效日期（无授权时为 null）
 * @param authorizationId 命中/否决所依据的授权版本标识（无授权时为 null）
 */
public record AuthorizationDecision(boolean allowed, List<String> reasonCodes, String level,
        LocalDate validUntil, String authorizationId) {

    /** 规整为不可变列表。 */
    public AuthorizationDecision {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
    }
}
