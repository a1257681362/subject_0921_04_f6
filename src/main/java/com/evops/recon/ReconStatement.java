package com.evops.recon;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账单：日结后一网点一业务日一份。
 * GENERATED 状态下允许随勾稽重跑刷新口径；CONFIRMED 后冻结，再重跑该业务日被拒绝。
 */
@TableName("t_recon_statement")
public class ReconStatement extends BizEntity {

    public static final String STATUS_GENERATED = "GENERATED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    private String statementNo;
    private String branchCode;
    private LocalDate bizDate;
    private String status;
    private Integer internalCount;
    private Integer externalCount;
    private Integer matchedCount;
    private Integer diffOpenCount;
    private Integer diffResolvedCount;
    private BigDecimal totalInternalAmount;
    private BigDecimal totalExternalAmount;
    private BigDecimal gapAmount;
    private String generatedBy;
    private LocalDateTime generatedTime;
    private String confirmedBy;
    private LocalDateTime confirmedTime;
    private String confirmRemark;

    public String getStatementNo() { return statementNo; }
    public void setStatementNo(String statementNo) { this.statementNo = statementNo; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getInternalCount() { return internalCount; }
    public void setInternalCount(Integer internalCount) { this.internalCount = internalCount; }
    public Integer getExternalCount() { return externalCount; }
    public void setExternalCount(Integer externalCount) { this.externalCount = externalCount; }
    public Integer getMatchedCount() { return matchedCount; }
    public void setMatchedCount(Integer matchedCount) { this.matchedCount = matchedCount; }
    public Integer getDiffOpenCount() { return diffOpenCount; }
    public void setDiffOpenCount(Integer diffOpenCount) { this.diffOpenCount = diffOpenCount; }
    public Integer getDiffResolvedCount() { return diffResolvedCount; }
    public void setDiffResolvedCount(Integer diffResolvedCount) { this.diffResolvedCount = diffResolvedCount; }
    public BigDecimal getTotalInternalAmount() { return totalInternalAmount; }
    public void setTotalInternalAmount(BigDecimal totalInternalAmount) { this.totalInternalAmount = totalInternalAmount; }
    public BigDecimal getTotalExternalAmount() { return totalExternalAmount; }
    public void setTotalExternalAmount(BigDecimal totalExternalAmount) { this.totalExternalAmount = totalExternalAmount; }
    public BigDecimal getGapAmount() { return gapAmount; }
    public void setGapAmount(BigDecimal gapAmount) { this.gapAmount = gapAmount; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public LocalDateTime getGeneratedTime() { return generatedTime; }
    public void setGeneratedTime(LocalDateTime generatedTime) { this.generatedTime = generatedTime; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }
    public LocalDateTime getConfirmedTime() { return confirmedTime; }
    public void setConfirmedTime(LocalDateTime confirmedTime) { this.confirmedTime = confirmedTime; }
    public String getConfirmRemark() { return confirmRemark; }
    public void setConfirmRemark(String confirmRemark) { this.confirmRemark = confirmRemark; }
}
