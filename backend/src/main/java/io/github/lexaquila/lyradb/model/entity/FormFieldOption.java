package io.github.lexaquila.lyradb.model.entity;

import lombok.Data;

/**
 * 表单字段下拉选项
 *
 * <p>
 * 用于连接表单中type=select的字段（如MaxCompute的Endpoint下拉选择）。
 * </p>
 */
@Data
public class FormFieldOption {

    /** 显示标签 */
    private String label;

    /** 选项值 */
    private String value;
}
