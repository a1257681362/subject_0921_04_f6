package com.evops.recon;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 周期作业运行记录：夜间配置批处理（CONFIG_APPLY）与凌晨日结对账（DAILY_RECON）
 * 每次触发逐次留痕，网点负责人次日早晨据此核对夜里的处理结果。
 * 作业为系统触发，不随行业务四要素，操作人信息写在 summary 里。
 */
@TableName("t_recon_job_run")
public class ReconJobRun extends BaseEntity {

    public static final String JOB_CONFIG_APPLY = "CONFIG_APPLY";
    public static final String JOB_DAILY_RECON = "DAILY_RECON";

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private String jobType;
    private LocalDateTime firedAt;
    private LocalDateTime finishedAt;
    private String status;
    private String branchCode;
    private LocalDate bizDate;
    private String summary;
    private String errorDetail;

    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public LocalDateTime getFiredAt() { return firedAt; }
    public void setFiredAt(LocalDateTime firedAt) { this.firedAt = firedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public LocalDate getBizDate() { return bizDate; }
    public void setBizDate(LocalDate bizDate) { this.bizDate = bizDate; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
}
