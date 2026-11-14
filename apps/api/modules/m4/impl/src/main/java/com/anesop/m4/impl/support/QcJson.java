package com.anesop.m4.impl.support;

import com.anesop.contracts.qc.model.ChartReviewItem;
import com.anesop.contracts.qc.model.RcaTimelineItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 质控域 jsonb 编解码（评分项 / RCA 时间线，M4-M17-01）。
 *
 * <p>只处理两个固定结构：病历抽查评分项数组与 RCA 时间线数组；写入前由服务层完成
 * 去重、排序与长度校验。读取失败视为数据损坏（500），**不静默降级为空列表**——
 * 静默降级会隐藏缺陷项或时间线，属于质控留痕完整性问题。
 */
@Component
public class QcJson {

    private static final TypeReference<List<ChartReviewItem>> ITEMS =
            new TypeReference<>() {
            };
    private static final TypeReference<List<RcaTimelineItem>> TIMELINE =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 ObjectMapper 由容器管理，本类不修改其配置")
    public QcJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 评分项数组 → jsonb 文本；null/空返回 {@code []}。 */
    public String writeItems(List<ChartReviewItem> items) {
        try {
            return objectMapper.writeValueAsString(items == null ? List.of() : items);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("质控评分项 jsonb 序列化失败", failure);
        }
    }

    /** jsonb 文本 → 评分项数组；空白返回空列表，非法 JSON 抛 IllegalStateException。 */
    public List<ChartReviewItem> readItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ChartReviewItem> items = objectMapper.readValue(json, ITEMS);
            return items == null ? List.of() : new ArrayList<>(items);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("质控评分项 jsonb 解析失败", failure);
        }
    }

    /** RCA 时间线 → jsonb 文本。 */
    public String writeTimeline(List<RcaTimelineItem> timeline) {
        try {
            return objectMapper.writeValueAsString(timeline == null ? List.of() : timeline);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("RCA 时间线 jsonb 序列化失败", failure);
        }
    }

    /** jsonb 文本 → RCA 时间线；空白返回空列表，非法 JSON 抛 IllegalStateException。 */
    public List<RcaTimelineItem> readTimeline(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<RcaTimelineItem> timeline = objectMapper.readValue(json, TIMELINE);
            return timeline == null ? List.of() : new ArrayList<>(timeline);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("RCA 时间线 jsonb 解析失败", failure);
        }
    }
}
