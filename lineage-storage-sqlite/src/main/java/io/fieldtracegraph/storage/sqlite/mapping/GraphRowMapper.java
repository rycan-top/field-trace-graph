package io.fieldtracegraph.storage.sqlite.mapping;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fieldtracegraph.core.model.CallEdgeRecord;
import io.fieldtracegraph.core.model.DataEdgeRecord;
import io.fieldtracegraph.core.model.EdgeEvidence;
import io.fieldtracegraph.core.model.PendingRefRecord;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.ValueNodeRecord;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

// 作用：把数据库行映射为模型记录。
// 使用场景：所有图查询结果的装配。

/**
 * Converts JDBC rows to immutable domain records. It owns no SQL and no
 * connection; DAOs call it after executing their own queries.
 */
public final class GraphRowMapper {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 作用：阻止工具类被实例化；逻辑：私有空构造器配合静态方法使用。
    private GraphRowMapper() {
    }

    // 作用：把结果行映射为值节点；逻辑：按列数判断是否带来源字段并逐列装配记录。
    public static ValueNodeRecord valueNode(ResultSet resultSet) throws SQLException {
        boolean hasOrigin = resultSet.getMetaData().getColumnCount() >= 19;
        return new ValueNodeRecord(
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getString(4),
                resultSet.getString(5),
                resultSet.getString(6),
                jsonList(resultSet.getString(7)),
                resultSet.getInt(8),
                resultSet.getInt(9),
                resultSet.getString(10),
                resultSet.getInt(11),
                resultSet.getInt(12),
                resultSet.getInt(13),
                resultSet.getInt(14),
                hasOrigin ? resultSet.getString(15) : null,
                hasOrigin ? resultSet.getInt(16) : 0,
                hasOrigin ? resultSet.getInt(17) : 0,
                hasOrigin ? resultSet.getInt(18) : 0,
                hasOrigin ? resultSet.getInt(19) : 0
        );
    }

    // 作用：把结果行映射为数据边；逻辑：按列数判断是否带来源字段，缺失时回退为源码来源。
    public static DataEdgeRecord dataEdge(ResultSet resultSet) throws SQLException {
        boolean hasProvenance = resultSet.getMetaData().getColumnCount() >= 17;
        return new DataEdgeRecord(
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getString(4),
                resultSet.getString(5),
                resultSet.getString(6),
                resultSet.getString(7),
                resultSet.getString(8),
                resultSet.getString(9),
                resultSet.getString(10),
                resultSet.getString(11),
                resultSet.getInt(12),
                resultSet.getInt(13),
                resultSet.getInt(14),
                resultSet.getInt(15),
                resultSet.getInt(16),
                hasProvenance ? resultSet.getString(17) : "SOURCE"
        );
    }

    // 作用：把结果行映射为边证据；逻辑：按证据表列顺序逐列装配记录。
    public static EdgeEvidence edgeEvidence(ResultSet resultSet) throws SQLException {
        return new EdgeEvidence(
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getString(4),
                resultSet.getString(5),
                resultSet.getString(6),
                resultSet.getString(7),
                resultSet.getString(8),
                resultSet.getString(9),
                resultSet.getInt(10),
                resultSet.getInt(11),
                resultSet.getInt(12),
                resultSet.getInt(13),
                resultSet.getString(14),
                resultSet.getString(15),
                resultSet.getString(16),
                resultSet.getString(17)
        );
    }

    // 作用：把结果行映射为调用边；逻辑：解析参数 JSON 并逐列装配记录。
    public static CallEdgeRecord callEdge(ResultSet resultSet) throws SQLException {
        return new CallEdgeRecord(
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getString(4),
                resultSet.getString(5),
                resultSet.getString(6),
                resultSet.getString(7),
                resultSet.getString(8),
                jsonList(resultSet.getString(9)),
                resultSet.getString(10),
                resultSet.getString(11),
                resultSet.getInt(12),
                resultSet.getInt(13),
                resultSet.getInt(14),
                resultSet.getInt(15)
        );
    }

    // 作用：把结果行映射为未解析引用；逻辑：解析候选与参数 JSON 并逐列装配记录。
    public static PendingRefRecord pendingRef(ResultSet resultSet) throws SQLException {
        return new PendingRefRecord(
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getString(4),
                resultSet.getString(5),
                jsonList(resultSet.getString(7)),
                resultSet.getString(6),
                resultSet.getString(8),
                resultSet.getInt(9),
                resultSet.getInt(10),
                resultSet.getInt(11),
                resultSet.getInt(12),
                resultSet.getString(13),
                resultSet.getString(14),
                jsonList(resultSet.getString(15)),
                resultSet.getString(16)
        );
    }

    // 作用：把结果行映射为源文件；逻辑：只读取元数据列，内容相关字段留空。
    public static SourceFileRecord sourceFile(ResultSet resultSet) throws SQLException {
        return new SourceFileRecord(
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getString(4),
                resultSet.getLong(5),
                resultSet.getString(6),
                resultSet.getString(7),
                resultSet.getString(8),
                null,
                null,
                null
        );
    }

    // 作用：解析字符串 JSON 数组；逻辑：空值返回空列表，否则反序列化为字符串集合。
    public static List<String> jsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse JSON list", e);
        }
    }

    // 作用：从全限定名提取方法签名；逻辑：截取井号后的部分，无分隔符时原样返回。
    public static String signatureFromQname(String qname) {
        int separator = qname.indexOf('#');
        return separator < 0 ? qname : qname.substring(separator + 1);
    }
}
