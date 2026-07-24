package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.model.entity.ConnectionConfig;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接配置DTO（API传输）
 *
 * <p>
 * 前端通过此DTO创建/修改连接，密码/AK-SK等敏感字段在传输时明文，
 * 后端接收后加密存储。返回前端时密码字段会被掩码处理。
 * </p>
 */
@Data
public class ConnectionDTO {

    private String id;
    private String name;
    private String dbType;
    private String displayName;
    private Map<String, Object> params = new HashMap<>();
    private String group;
    private String color;
    private String description;
    private List<String> tags;
    private Boolean favorite;
    private Integer sortOrder;
    private Boolean autoConnect;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 当前连接状态 */
    private String status = "DISCONNECTED"; // CONNECTED / DISCONNECTED / ERROR

    /** 错误信息 */
    private String errorMessage;

    /**
     * 从实体转换为DTO
     */
    public static ConnectionDTO fromEntity(ConnectionConfig entity, Map<String, Object> params) {
        ConnectionDTO dto = new ConnectionDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDbType(entity.getDbType());
        dto.setDisplayName(entity.getDisplayName());
        dto.setParams(params != null ? params : new HashMap<>());
        dto.setGroup(entity.getGroup());
        dto.setColor(entity.getColor());
        dto.setDescription(entity.getDescription());
        dto.setTags(parseTags(entity.getTags()));
        dto.setFavorite(entity.getFavorite());
        dto.setSortOrder(entity.getSortOrder());
        dto.setAutoConnect(entity.getAutoConnect());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    /**
     * 解析标签字符串为列表
     */
    private static List<String> parseTags(String tagsStr) {
        if (tagsStr == null || tagsStr.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(tagsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());
    }
}
