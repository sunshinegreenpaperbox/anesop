package com.anesop.simulators;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 模拟器内部 Jackson 配置（不放宽未知字段校验，配置写错必须启动失败）。 */
public final class SimulatorJson {

    private SimulatorJson() {
    }

    /**
     * 创建模拟器专用 ObjectMapper。
     *
     * <p>保持 {@code FAIL_ON_UNKNOWN_PROPERTIES=true}：JSON 配置里的拼写错误应立即失败，
     * 不能静默忽略（fail-closed）。
     *
     * @return 新的 ObjectMapper（可安全地按进程复用）
     */
    public static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
