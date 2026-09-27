package com.evops.rental;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 交接记录：交车 PICKUP / 还车 RETURN。
 * 两类交接都必须登记油量表读数与车损照片。
 */
@TableName("t_rental_handover")
public class RentalHandover extends BizEntity {

    private Long orderId;

    /** PICKUP / RETURN */
    private String type;

    /** 油量表读数（0-100） */
    private Integer fuelReading;

    /** 车损照片地址，多个以逗号分隔 */
    private String damagePhotos;

    private String remark;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getFuelReading() { return fuelReading; }
    public void setFuelReading(Integer fuelReading) { this.fuelReading = fuelReading; }
    public String getDamagePhotos() { return damagePhotos; }
    public void setDamagePhotos(String damagePhotos) { this.damagePhotos = damagePhotos; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
