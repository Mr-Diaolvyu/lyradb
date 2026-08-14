package io.github.lexaquila.lyradb.model.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MaxCompute 单个分区的只读元数据。
 *
 * <p>{@code spec} 保留服务端返回的规范形式（例如
 * {@code ds=20260814/region=hangzhou}）；调用方必须把它作为不透明值传回
 * Core，不应自行拼接 SQL。</p>
 */
public class PartitionMetadata {

    private String spec = "";
    private Map<String, String> values = new LinkedHashMap<>();

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec == null ? "" : spec;
    }

    public Map<String, String> getValues() {
        return values;
    }

    public void setValues(Map<String, String> values) {
        this.values = values == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }
}
