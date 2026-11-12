package com.anesop.m1.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 患者排程侧快照实体，对应 {@code m1_sched.patient_snapshot}（V7）。
 *
 * <p>只保存最小必要展示字段与住院号哈希；不保存身份证、手机号或住院号明文。
 */
@TableName("m1_sched.patient_snapshot")
public class PatientSnapshotEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String hisPatientNo;
    private String name;
    private String gender;
    private LocalDate birthDate;
    private Integer age;
    private String inpatientNoMasked;
    private String inpatientNoHash;
    private String deptName;
    private UUID deptId;
    private String ward;
    private String bedNo;
    private UUID siteId;
    private Instant createdAt;
    private UUID createdBy;
    private Instant updatedAt;
    private UUID updatedBy;

    @Version
    private Long version;

    @TableLogic(value = "false", delval = "true")
    private Boolean deleted;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getHisPatientNo() {
        return hisPatientNo;
    }

    public void setHisPatientNo(String hisPatientNo) {
        this.hisPatientNo = hisPatientNo;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getInpatientNoMasked() {
        return inpatientNoMasked;
    }

    public void setInpatientNoMasked(String inpatientNoMasked) {
        this.inpatientNoMasked = inpatientNoMasked;
    }

    public String getInpatientNoHash() {
        return inpatientNoHash;
    }

    public void setInpatientNoHash(String inpatientNoHash) {
        this.inpatientNoHash = inpatientNoHash;
    }

    public String getDeptName() {
        return deptName;
    }

    public void setDeptName(String deptName) {
        this.deptName = deptName;
    }

    public UUID getDeptId() {
        return deptId;
    }

    public void setDeptId(UUID deptId) {
        this.deptId = deptId;
    }

    public String getWard() {
        return ward;
    }

    public void setWard(String ward) {
        this.ward = ward;
    }

    public String getBedNo() {
        return bedNo;
    }

    public void setBedNo(String bedNo) {
        this.bedNo = bedNo;
    }

    public UUID getSiteId() {
        return siteId;
    }

    public void setSiteId(UUID siteId) {
        this.siteId = siteId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
