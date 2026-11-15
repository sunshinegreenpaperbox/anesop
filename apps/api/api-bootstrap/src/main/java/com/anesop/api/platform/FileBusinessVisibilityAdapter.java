package com.anesop.api.platform;

import com.anesop.m0.api.platform.FileBusinessVisibilityPort;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.record.AnesthesiaRecordService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文件业务可见性适配器（M8-M0-04 评审 B-1）。
 *
 * <p>装配层把 m0 的 {@link FileBusinessVisibilityPort} 适配到 m1/m2 的既有 DataScope 查询，
 * 避免 m0 直接依赖业务模块（模块边界：m0 是底座）。判定语义：
 * <ul>
 *   <li>{@code SCHEDULE} → m1 {@link SchedulingService#get(String, AuthPrincipal)}：
 *       台次不存在/已删除/超出 DEPT/SELF/ROOM/SITE 数据范围 → 业务服务抛 404 → false；</li>
 *   <li>{@code RECORD} → m2 {@link AnesthesiaRecordService#get(String, AuthPrincipal)}：
 *       记录或所属台次超出数据范围 → 404 → false；</li>
 *   <li>{@code REPORT}：当前 M4 无持久化报表对象标识/范围查询，<b>fail-closed</b>（false），
 *       仅上传者/SYS_ADMIN 可见；待报表快照口径确认后接入（decisions.md Q-24）；</li>
 *   <li>其他类型/空标识/异常 → false，绝不因适配器故障放行。</li>
 * </ul>
 *
 * <p>日志只记录业务类型，不记录 businessId、患者标识或异常消息（避免把业务标识写入日志）。
 */
@Component
public class FileBusinessVisibilityAdapter implements FileBusinessVisibilityPort {

    private static final Logger LOG = LoggerFactory.getLogger(FileBusinessVisibilityAdapter.class);

    private final SchedulingService schedulingService;
    private final AnesthesiaRecordService recordService;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 单例注入的领域服务，生命周期由容器管理，适配器不对外暴露引用")
    public FileBusinessVisibilityAdapter(SchedulingService schedulingService,
            AnesthesiaRecordService recordService) {
        this.schedulingService = schedulingService;
        this.recordService = recordService;
    }

    @Override
    public boolean isVisible(AuthPrincipal caller, String businessType, String businessId) {
        if (caller == null || caller.userId() == null || businessId == null || businessId.isBlank()) {
            return false;
        }
        try {
            return switch (businessType == null ? "" : businessType) {
                case "SCHEDULE" -> {
                    schedulingService.get(businessId, caller);
                    yield true;
                }
                case "RECORD" -> {
                    recordService.get(businessId, caller);
                    yield true;
                }
                // REPORT 无对象标识 → fail-closed（Q-24）；TEMPLATE/ATTACHMENT 由 FileService 处理。
                default -> false;
            };
        } catch (RuntimeException e) {
            // 包含 NoSuchElementException（不存在/超范围）与 AccessDeniedException；
            // 其他运行时异常同样按不可见处理（fail-closed），不把异常细节回显给调用方。
            LOG.warn("文件业务可见性判定失败，按不可见处理：businessType={}，reason={}",
                    businessType, e.getClass().getSimpleName());
            return false;
        }
    }
}
