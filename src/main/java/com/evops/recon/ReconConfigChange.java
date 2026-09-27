package com.evops.recon;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.time.LocalDateTime;

/**
 * 配置变更单：白天不敢动配置，调整先登记为 PENDING，夜间批处理统一校验后置 APPLIED
 * 并改写 t_recon_config；非法（如负数容差）置 REJECTED 并留驳回原因，次日核对可见。
 */
@TableName("t_recon_config_change")
public class ReconConfigChange extends BizEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_REJECTED = "REJECTED";

    private String changeNo;
    private String configKey;
    private String oldValue;
    private String newValue;
    private String reason;
    private String status;
    private String rejectReason;
    private LocalDateTime appliedTime;
    private Long appliedJobRunId;

    public String getChangeNo() { return changeNo; }
    public void setChangeNo(String changeNo) { this.changeNo = changeNo; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public LocalDateTime getAppliedTime() { return appliedTime; }
    public void setAppliedTime(LocalDateTime appliedTime) { this.appliedTime = appliedTime; }
    public Long getAppliedJobRunId() { return appliedJobRunId; }
    public void setAppliedJobRunId(Long appliedJobRunId) { this.appliedJobRunId = appliedJobRunId; }
}
