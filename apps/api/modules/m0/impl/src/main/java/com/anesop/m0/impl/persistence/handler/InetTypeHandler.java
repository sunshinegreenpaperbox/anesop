package com.anesop.m0.impl.persistence.handler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

/**
 * PostgreSQL {@code inet} 与 Java {@link String} 的 TypeHandler。
 *
 * <p>PG JDBC 会把普通 String 绑定为 varchar，无法直接写入 inet 列；此处显式包装为
 * {@link PGobject}（type=inet）。读取时经 {@code getString} 返回文本形式（IPv4/IPv6）。
 *
 * <p>只用于 m0_platform 的 inet 列（当前为 sys_audit_log.ip）。D-04 信创适配时
 * 在此集中替换为 varchar(45) + 应用侧校验，见 docs/db/README.md。
 */
public class InetTypeHandler extends BaseTypeHandler<String> {

    private static final String PG_TYPE = "inet";

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
