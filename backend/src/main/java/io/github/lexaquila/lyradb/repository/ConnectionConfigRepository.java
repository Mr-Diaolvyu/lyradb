package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.ConnectionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 连接配置JPA仓储
 */
@Repository
public interface ConnectionConfigRepository extends JpaRepository<ConnectionConfig, String> {

    /**
     * 按分组查找连接
     */
    List<ConnectionConfig> findByGroupOrderByCreatedAtAsc(String group);

    /**
     * 按数据库类型查找连接
     */
    List<ConnectionConfig> findByDbTypeOrderByCreatedAtAsc(String dbType);

    /**
     * 查找所有连接（按创建时间排序）
     */
    List<ConnectionConfig> findAllByOrderByCreatedAtAsc();
}
