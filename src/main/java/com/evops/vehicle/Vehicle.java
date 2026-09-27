package com.evops.vehicle;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 车辆台账。
 */
@TableName("t_vehicle")
public class Vehicle extends BizEntity {

    /** 车牌号（库层唯一） */
    private String plateNo;

    /** 车型 */
    private String model;

    /** IDLE 在库 / RENTING 出租中 / DISABLED 停用 */
    private String status;

    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
