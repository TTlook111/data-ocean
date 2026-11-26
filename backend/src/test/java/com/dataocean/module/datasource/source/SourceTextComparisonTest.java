package com.dataocean.module.datasource.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.module.datasource.source.SourceConnection.Type;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * 字符等值判断使用实际列排序规则和源库返回的布尔值，不采样业务记录。
 */
class SourceTextComparisonTest {
    /**
     * 比较同一对值时，SQL 指定服务端返回的排序规则，并依赖数据库返回结果。
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void comparesWithCurrentColumnCollation(boolean equal) throws SQLException {
        var connection = mock(Connection.class);
        var metadata = mock(PreparedStatement.class);
        var comparison = mock(PreparedStatement.class);
        var details = mock(ResultSet.class);
        var result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(metadata, comparison);
        when(metadata.executeQuery()).thenReturn(details);
        when(details.next()).thenReturn(true, false);
        when(details.getString(1)).thenReturn("utf8mb4");
        var collation = equal ? "utf8mb4_0900_ai_ci" : "utf8mb4_0900_as_cs";
        when(details.getString(2)).thenReturn(collation);
        when(comparison.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(equal);

        var jdbc = new SourceJdbc(null);
        var object = new ObjectRef("sales", "", "orders", "TABLE", null, null, null);
        assertThat(jdbc.compareText(connection, Type.MYSQL, object, "payment_state", "PAID", "paid", 3))
                .isEqualTo(equal);

        // 目录用绑定值定位列，实际比较只执行同一声明排序规则下的标量查询。
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sql.capture());
        assertThat(sql.getAllValues().get(0)).contains("information_schema.COLUMNS", "column_name = ?");
        assertThat(sql.getAllValues().get(1))
                .isEqualTo("SELECT (CAST(? AS CHAR CHARACTER SET utf8mb4) COLLATE " + collation
                        + ") = (CAST(? AS CHAR CHARACTER SET utf8mb4) COLLATE " + collation + ")");
        verify(metadata).setString(1, "sales");
        verify(metadata).setString(2, "orders");
        verify(metadata).setString(3, "payment_state");
        verify(comparison).setString(1, "PAID");
        verify(comparison).setString(2, "paid");
    }
}
