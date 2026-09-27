package com.evops.recon;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账批次：一网点一业务日一次勾稽一行，可重跑。
 * 周期任务凌晨自动跑（SCHEDULED），日结出对账单（DAILY），也支持手工补跑（MANUAL）。
 */
@TableName("t_recon_batch")
public class ReconBatch extends BizEntity {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_DAILY = "DAILY";

    private String batchNo;
    private String branchCode;
    private LocalDate bizDate;
    private String status;
    private String triggerType;
    private Integer internalCount;
    private Integer externalCount;
    private Integer matchedCount;
    private Integer diffCount;
    private BigDecimal toleranceAmount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String remark;

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public Integer getInternalCount() { return internalCount; }
    public void setInternalCount(Integer internalCount) { this.internalCount = internalCount; }
    public Integer getExternalCount() { return externalCount; }
    public void setExternalCount(Integer externalCount) { this.externalCount = externalCount; }
    public Integer getMatchedCount() { return matchedCount; }
    public void setMatchedCount(Integer matchedCount) { this.matchedCount = matchedCount; }
    public Integer getDiffCount() { return diffCount; }
    public void setDiffCount(Integer diffCount) { this.diffCount = diffCount; }
    public BigDecimal getToleranceAmount() { return toleranceAmount; }
    public void setToleranceAmount(BigDecimal toleranceAmount) { this.toleranceAmount = toleranceAmount; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
