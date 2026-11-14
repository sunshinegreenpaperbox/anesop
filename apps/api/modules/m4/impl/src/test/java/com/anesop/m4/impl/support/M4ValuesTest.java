package com.anesop.m4.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.m4.api.error.M4ValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 人员域字段校验工具单测：trim、长度、可选值、UUID 格式与固定安全文案。 */
class M4ValuesTest {

    @Test
    void requireTextTrimsAndRejectsBlankOrTooLong() {
        assertThat(M4Values.requireText("  STAFF-1  ", "staffNo", 32)).isEqualTo("STAFF-1");
        assertThatThrownBy(() -> M4Values.requireText(null, "staffNo", 32))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> M4Values.requireText("   ", "staffNo", 32))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> M4Values.requireText("123456", "staffNo", 3))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("长度");
    }

    @Test
    void optionalTextReturnsNullForBlankAndRejectsTooLong() {
        assertThat(M4Values.optionalText(null, "note", 10)).isNull();
        assertThat(M4Values.optionalText("  ", "note", 10)).isNull();
        assertThat(M4Values.optionalText("  x  ", "note", 10)).isEqualTo("x");
        assertThatThrownBy(() -> M4Values.optionalText("123456", "note", 3))
                .isInstanceOf(M4ValidationException.class);
    }

    @Test
    void optionalUuidParsesOrRejects() {
        UUID id = UUID.randomUUID();
        assertThat(M4Values.optionalUuid(null, "userId")).isNull();
        assertThat(M4Values.optionalUuid(" ", "userId")).isNull();
        assertThat(M4Values.optionalUuid(id.toString(), "userId")).isEqualTo(id);
        assertThatThrownBy(() -> M4Values.optionalUuid("bad", "userId"))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("格式非法");
    }

    @Test
    void parseUuidToleratesInvalidInput() {
        UUID id = UUID.randomUUID();
        assertThat(M4Values.parseUuid(id.toString())).isEqualTo(id);
        assertThat(M4Values.parseUuid("bad")).isNull();
        assertThat(M4Values.parseUuid(null)).isNull();
        assertThat(M4Values.parseUuid("")).isNull();
    }
}
