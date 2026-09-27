package com.evops.telematics;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.time.LocalDateTime;

/**
 * 设备回执：定位平台上报的设备状态。业务键 (plate_no, device_no) 唯一，
 * 不同文件重复上报同一设备按最新内容覆盖。
 */
@TableName("t_telematics_device_report")
public class TelematicsDeviceReport extends BizEntity {

    private String plateNo;

    private String deviceNo;

    /** ONLINE / OFFLINE */
    private String deviceStatus;

    private LocalDateTime reportTime;

    private String remark;

    /** 来源导入文件 id */
    private Long importFileId;

    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getDeviceNo() { return deviceNo; }
    public void setDeviceNo(String deviceNo) { this.deviceNo = deviceNo; }
    public String getDeviceStatus() { return deviceStatus; }
    public void setDeviceStatus(String deviceStatus) { this.deviceStatus = deviceStatus; }
    public LocalDateTime getReportTime() { return reportTime; }
    public void setReportTime(LocalDateTime reportTime) { this.reportTime = reportTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getImportFileId() { return importFileId; }
    public void setImportFileId(Long importFileId) { this.importFileId = importFileId; }
}
