package com.anesop.m4.impl.support;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 人员域 jsonb 字符串数组编解码（专长 / 术式范围）。
 *
 * <p>只处理 {@code jsonb} 字符串数组：写入前由服务层完成去重/长度校验，读取失败视为数据
 * 损坏（500），不做静默降级——静默把损坏数据当空数组会放宽授权范围，属安全缺陷。
 */
@Component
public class StaffJson {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 ObjectMapper 由容器管理，本类不修改其配置")
    public StaffJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 字符串数组 → jsonb 文本；null/空返回 {@code []}。 */
    public String write(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("人员域 jsonb 序列化失败", failure);
        }
    }

    /** jsonb 文本 → 字符串数组；null/空白返回空列表，非法 JSON 抛 IllegalStateException。 */
    public List<String> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            return values == null ? List.of() : new ArrayList<>(values);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("人员域 jsonb 解析失败", failure);
        }
    }
}
