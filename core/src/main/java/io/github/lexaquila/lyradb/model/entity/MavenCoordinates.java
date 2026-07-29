package io.github.lexaquila.lyradb.model.entity;

import lombok.Data;

/**
 * Maven坐标信息
 *
 * <p>
 * 描述一个Maven制品的groupId/artifactId/version，
 * 用于Maven Resolver API动态下载驱动JAR包。
 * </p>
 */
@Data
public class MavenCoordinates {

    /** Maven groupId */
    private String groupId;

    /** Maven artifactId */
    private String artifactId;

    /** 版本号 */
    private String version;

    /** 分类器（如ClickHouse需要http classifier） */
    private String classifier;
}
