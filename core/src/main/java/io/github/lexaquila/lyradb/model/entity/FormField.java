package io.github.lexaquila.lyradb.model.entity;

import lombok.Data;
import java.util.List;

/**
 * 连接表单字段定义
 *
 * <p>
 * 从drivers.json加载，描述每种数据库连接表单需要哪些字段。
 * 前端根据此定义动态渲染连接表单（RDBMS填密码/MC填AK-SK/MongoDB填URI/Redis填密码）。
 * </p>
 *
 * <p>
 * 这是"按库类型动态渲染表单"核心体验的数据基础。
 * </p>
 */
@Data
public class FormField {

    /** 字段名（对应连接参数key） */
    private String name;

    /** 显示标签 */
    private String label;

    /** 字段类型：text/password/number/boolean/select */
    private String type;

    /** 是否必填 */
    private boolean required;

    /** 默认值 */
    private Object defaultValue;

    /** 下拉选项（仅type=select时使用） */
    private List<FormFieldOption> options;
}
