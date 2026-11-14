package com.anesop.api.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.anesop.m4.api.report.ReportCaseFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * 装配层报表投影的数据范围纵深防御（M4-M16-01 / S-1）。
 *
 * <p>服务层已在解析数据范围时 fail-closed；本用例锁定投影层的第二道闸：即使未来出现
 * 绕过服务层的调用，非全院且 {@code orgIds}/{@code roomIds}/SELF 全空的过滤器也**不得**
 * 退化为不带范围条件的全表查询（fail-open），必须在执行 SQL 前拒绝。
 */
@ExtendWith(MockitoExtension.class)
class ReportCaseSourceAdapterTest {

    private static final Instant FROM = Instant.parse("2031-03-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2031-04-01T00:00:00Z");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void refusesEmptyRestrictedScopeInsteadOfQueryingAllRows() {
        ReportCaseSourceAdapter adapter = new ReportCaseSourceAdapter(jdbcTemplate);
        ReportCaseFilter failOpen = new ReportCaseFilter(FROM, TO, null, false, List.of(),
                List.of(), UUID.randomUUID(), false);

        assertThatThrownBy(() -> adapter.findCases(failOpen))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("无可用报表数据范围");
        // 关闸在执行 SQL 之前：绝不下发无范围条件的查询。
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void nullFilterReturnsEmptyWithoutQuerying() {
        ReportCaseSourceAdapter adapter = new ReportCaseSourceAdapter(jdbcTemplate);

        assertThat(adapter.findCases(null)).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }
}
