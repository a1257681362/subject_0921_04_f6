package com.evops.recon;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 差异清单：金额超差 / 状态不一致 / 单边账。
 *
 * (branch_code, biz_date, diff_key) 唯一，支撑同业务日重跑：
 * - 重跑仍存在的 OPEN 差异原地刷新（最新批次、金额快照）；
 * - 重跑已消失且未人工核销的差异自动消解（resolve_type=AUTO）；
 * - 已人工核销（MANUAL）的差异永不翻案。
 * 人工核销必须填 resolve_reason，核销后差异即时清零（OPEN 口径立即不可见）。
 */
@TableName("t_recon_diff")
public class ReconDiff extends BizEntity {

    public static final String TYPE_AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    public static final String TYPE_STATUS_MISMATCH = "STATUS_MISMATCH";
    public static final String TYPE_AMOUNT_STATUS_MISMATCH = "AMOUNT_STATUS_MISMATCH";
    public static final String TYPE_INTERNAL_ONLY = "INTERNAL_ONLY";
    public static final String TYPE_EXTERNAL_ONLY = "EXTERNAL_ONLY";

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";

    public static final String RESOLVE_MANUAL = "MANUAL";
    public static final String RESOLVE_AUTO = "AUTO";

    private String branchCode;
    private LocalDate bizDate;
    private Long batchId;
    private String diffType;
    private String diffKey;
    private Long internalTxnId;
    private Long externalTxnId;
    private String refNo;
    private BigDecimal internalAmount;
    private BigDecimal externalAmount;
    private BigDecimal amountGap;
    private String internalStatus;
    private String externalStatus;
    private String status;
    private String resolveType;
    private String resolveReason;
    private String resolveOperator;
    private LocalDateTime resolveTime;

    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getDiffType() { return diffType; }
    public void setDiffType(String diffType) { this.diffType = diffType; }
    public String getDiffKey() { return diffKey; }
    public void setDiffKey(String diffKey) { this.diffKey = diffKey; }
    public Long getInternalTxnId() { return internalTxnId; }
    public void setInternalTxnId(Long internalTxnId) { this.internalTxnId = internalTxnId; }
    public Long getExternalTxnId() { return externalTxnId; }
    public void setExternalTxnId(Long externalTxnId) { this.externalTxnId = externalTxnId; }
    public String getRefNo() { return refNo; }
    public void setRefNo(String refNo) { this.refNo = refNo; }
    public BigDecimal getInternalAmount() { return internalAmount; }
    public void setInternalAmount(BigDecimal internalAmount) { this.internalAmount = internalAmount; }
    public BigDecimal getExternalAmount() { return externalAmount; }
    public void setExternalAmount(BigDecimal externalAmount) { this.externalAmount = externalAmount; }
    public BigDecimal getAmountGap() { return amountGap; }
    public void setAmountGap(BigDecimal amountGap) { this.amountGap = amountGap; }
    public String getInternalStatus() { return internalStatus; }
    public void setInternalStatus(String internalStatus) { this.internalStatus = internalStatus; }
    public String getExternalStatus() { return externalStatus; }
    public void setExternalStatus(String externalStatus) { this.externalStatus = externalStatus; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolveType() { return resolveType; }
    public void setResolveType(String resolveType) { this.resolveType = resolveType; }
    public String getResolveReason() { return resolveReason; }
    public void setResolveReason(String resolveReason) { this.resolveReason = resolveReason; }
    public String getResolveOperator() { return resolveOperator; }
    public void setResolveOperator(String resolveOperator) { this.resolveOperator = resolveOperator; }
    public LocalDateTime getResolveTime() { return resolveTime; }
    public void setResolveTime(LocalDateTime resolveTime) { this.resolveTime = resolveTime; }
}
