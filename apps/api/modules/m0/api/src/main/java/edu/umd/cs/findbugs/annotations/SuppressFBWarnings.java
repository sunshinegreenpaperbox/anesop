package edu.umd.cs.findbugs.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SpotBugs 抑制注解（工程内最小声明，非第三方依赖）。
 *
 * <p>背景（M2-M0-02 评审 Q-1）：Spring 单例通过构造器注入 MyBatis Mapper 代理时，
 * SpotBugs 会把“把外部可变对象存入字段”报为 {@code EI_EXPOSE_REP2}。这是注入型 Bean 的
 * 已知误报：Mapper 代理由容器创建、生命周期与单例一致，服务不向外部暴露该引用。
 *
 * <p>不能使用 {@code @SuppressWarnings}：其保留策略是 SOURCE，不会进入字节码，SpotBugs
 * 无从识别。工程纪律不允许为静态分析新增第三方依赖，因此这里声明与官方注解同名、同保留
 * 策略（CLASS）的最小注解；SpotBugs 按全限定名识别，抑制行为与官方
 * {@code com.github.spotbugs:spotbugs-annotations} 一致。
 *
 * <p>若后续经依赖评审与 allowlist 登记引入官方 {@code spotbugs-annotations}，删除本文件即可。
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.CONSTRUCTOR, ElementType.LOCAL_VARIABLE, ElementType.ANNOTATION_TYPE,
        ElementType.PACKAGE})
public @interface SuppressFBWarnings {

    /** 要抑制的 SpotBugs 缺陷模式（如 {@code EI_EXPOSE_REP2}）。 */
    String[] value() default {};

    /** 抑制理由；本工程要求写明为何是误报，便于评审。 */
    String justification() default "";
}
