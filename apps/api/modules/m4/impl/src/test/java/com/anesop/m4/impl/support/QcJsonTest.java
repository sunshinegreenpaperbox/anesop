package com.anesop.m4.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.contracts.qc.model.ChartReviewItem;
import com.anesop.contracts.qc.model.RcaTimelineItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 质控 jsonb 编解码单测（M4-M17-01）：评分项/时间线往返、空值回退与损坏数据 fail-closed。
 *
 * <p>损坏 JSON 必须抛 {@link IllegalStateException}（500），不得静默降级为空列表——
 * 静默降级会隐藏缺陷项或时间线，破坏质控留痕完整性。
 */
class QcJsonTest {

    private QcJson json;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        json = new QcJson(mapper);
    }

    @Test
    void chartReviewItemsRoundTripAndBlankFallsBackToEmpty() {
        ChartReviewItem item = new ChartReviewItem();
        item.setItemCode(ChartReviewItem.ItemCodeEnum.COMPLETENESS);
        item.setItemName("完整性");
        item.setScore(28);
        item.setMaxScore(30);
        item.setDefectNote("缺术后随访（合成）");

        String encoded = json.writeItems(List.of(item));
        List<ChartReviewItem> decoded = json.readItems(encoded);

        assertThat(decoded).hasSize(1);
        assertThat(decoded.get(0).getItemCode())
                .isEqualTo(ChartReviewItem.ItemCodeEnum.COMPLETENESS);
        assertThat(decoded.get(0).getScore()).isEqualTo(28);
        assertThat(decoded.get(0).getDefectNote()).isEqualTo("缺术后随访（合成）");
        assertThat(json.writeItems(null)).isEqualTo("[]");
        assertThat(json.readItems(null)).isEmpty();
        assertThat(json.readItems(" ")).isEmpty();
    }

    @Test
    void rcaTimelineRoundTripKeepsTimestamps() {
        RcaTimelineItem first = new RcaTimelineItem();
        first.setOccurredAt(OffsetDateTime.of(2031, 3, 2, 10, 30, 0, 0, ZoneOffset.ofHours(8)));
        first.setDescription("诱导后 3 分钟血压下降（合成）");

        String encoded = json.writeTimeline(List.of(first));
        List<RcaTimelineItem> decoded = json.readTimeline(encoded);

        assertThat(decoded).hasSize(1);
        assertThat(decoded.get(0).getDescription()).contains("血压下降");
        assertThat(decoded.get(0).getOccurredAt().toInstant())
                .isEqualTo(first.getOccurredAt().toInstant());
        assertThat(json.writeTimeline(null)).isEqualTo("[]");
        assertThat(json.readTimeline(null)).isEmpty();
    }

    @Test
    void corruptJsonFailsClosedInsteadOfSilentlyEmptying() {
        assertThatThrownBy(() -> json.readItems("{not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析失败");
        assertThatThrownBy(() -> json.readTimeline("[{\"occurredAt\":\"bad\"}]"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析失败");
    }
}
