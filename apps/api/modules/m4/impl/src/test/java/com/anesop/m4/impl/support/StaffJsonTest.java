package com.anesop.m4.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 人员域 jsonb 字符串数组编解码单测：null/空、正常读写与损坏数据 fail-fast。 */
class StaffJsonTest {

    private final StaffJson staffJson = new StaffJson(new ObjectMapper());

    @Test
    void writesNullAsEmptyArray() {
        assertThat(staffJson.write(null)).isEqualTo("[]");
        assertThat(staffJson.write(List.of("A", "B"))).isEqualTo("[\"A\",\"B\"]");
    }

    @Test
    void readsNullOrBlankAsEmptyList() {
        assertThat(staffJson.read(null)).isEmpty();
        assertThat(staffJson.read("  ")).isEmpty();
    }

    @Test
    void readsJsonArray() {
        assertThat(staffJson.read("[\"*\",\"GENERAL\"]")).containsExactly("*", "GENERAL");
    }

    @Test
    void brokenJsonFailsFastInsteadOfWideningScope() {
        assertThatThrownBy(() -> staffJson.read("not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析失败");
        assertThatThrownBy(() -> staffJson.read("{\"a\":1}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
