package com.anesop.m0.impl.persistence.handler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/**
 * PostgreSQL {@code jsonb} 与 Java {@link String} 的 TypeHandler。
 *
 * <p>PG JDBC 会把普通 String 绑定为 varchar，无法直接写入 jsonb 列；此处显式包装为
 * {@link PGobject}（type=jsonb）。读取时经 {@code getString} 返回 JSON 文本。
 *
 * <p>只用于 m0_platform 的 jsonb 列（当前为 sys_audit_log.detail）。D-04 信创适配时
 * 在此集中替换为 json/text 映射，见 docs/db/README.md。
 */
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    private static final String PG_TYPE = "jsonb";

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject value = new PGobject();
        value.setType(PG_TYPE);
        value.setValue(parameter);
        ps.setObject(i, value);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
