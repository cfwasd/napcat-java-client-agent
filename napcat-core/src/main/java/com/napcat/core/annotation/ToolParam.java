package com.napcat.core.annotation;

import java.lang.annotation.*;

/**
 * 工具参数注解。标注在 @Tool 方法的参数上。
 * <p>
 * {@code value()} 定义参数在 JSON Schema 中的名称（即 LLM function calling 生成的 JSON key），
 * 建议使用简短英文标识符（如 "cron", "name", "query"）。
 * <p>
 * {@code description()} 定义参数的中文描述，会出现在 Schema 的 description 字段中供 LLM 理解。
 * 若不指定 value()，默认沿用 description() 的值作为 key（向后兼容）。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {
    /** 参数在 JSON Schema 中的键名。未指定时使用 description 的值（向后兼容） */
    String value() default "";
    /** 参数描述 */
    String description();
    /** 是否必填 */
    boolean required() default false;
    /** 可选枚举值列表 */
    String[] enums() default {};
    /** 参数类型 */
    String type() default "string";
}
