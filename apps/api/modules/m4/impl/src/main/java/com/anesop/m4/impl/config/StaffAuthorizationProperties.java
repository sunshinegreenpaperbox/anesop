package com.anesop.m4.impl.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分级授权口径配置（M4-M13-01，级别默认映射【待确认】）。
 *
 * <p>方案书只要求“分级授权 R1–R5 + 术式/年龄段/ASA 映射”，未给出可执行的临床口径；
 * 为避免臆造授权规则（AGENTS H-04/H-06），系统**默认不内置任何级别映射**：授权范围以
 * 审批人在 {@code staff_authorization} 录入的显式字段为权威。
 *
 * <p>院方确认授权目录后，可通过下列配置叠加“级别上限”校验（无需改表或契约）：
 * <pre>
 * anesop:
 *   m4:
 *     staff:
 *       authorization:
 *         level-policies:
 *           R3:
 *             asa-max: ASA3
 *             age-min: 18
 *             age-max: 90
 *             surgery-scopes: ["*"]
 * </pre>
 *
 * <p>启用后：授予/复评时超出级别策略 → 422；校验时超出 → {@code allowed=false} +
 * {@code LEVEL_POLICY_EXCEEDED}（与显式字段校验叠加，取更严格者）。
 */
@ConfigurationProperties(prefix = "anesop.m4.staff.authorization")
public class StaffAuthorizationProperties {

    /** 级别 → 策略上限；默认为空（不内置映射，【待确认】）。 */
    private Map<String, LevelPolicy> levelPolicies = new LinkedHashMap<>();

    @SuppressFBWarnings(value = "EI_EXPOSE_REP",
            justification = "配置对象由 Spring 绑定、启动后只读；返回内部 Map 以避免每次读取复制")
    public Map<String, LevelPolicy> getLevelPolicies() {
        return levelPolicies;
    }

    public void setLevelPolicies(Map<String, LevelPolicy> levelPolicies) {
        this.levelPolicies = levelPolicies == null ? new LinkedHashMap<>() : levelPolicies;
    }

    /** 单个级别的上限策略（字段为 null 表示该维度不限制）。 */
    public static class LevelPolicy {

        private String asaMax;
        private Integer ageMin;
        private Integer ageMax;
        private List<String> surgeryScopes = new ArrayList<>();

        public String getAsaMax() {
            return asaMax;
        }

        public void setAsaMax(String asaMax) {
            this.asaMax = asaMax;
        }

        public Integer getAgeMin() {
            return ageMin;
        }

        public void setAgeMin(Integer ageMin) {
            this.ageMin = ageMin;
        }

        public Integer getAgeMax() {
            return ageMax;
        }

        public void setAgeMax(Integer ageMax) {
            this.ageMax = ageMax;
        }

        @SuppressFBWarnings(value = "EI_EXPOSE_REP",
                justification = "配置对象由 Spring 绑定、启动后只读；返回内部 List 以避免每次读取复制")
        public List<String> getSurgeryScopes() {
            return surgeryScopes;
        }

        public void setSurgeryScopes(List<String> surgeryScopes) {
            this.surgeryScopes = surgeryScopes == null ? new ArrayList<>() : surgeryScopes;
        }
    }
}
