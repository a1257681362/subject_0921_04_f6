package com.evops.rental.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 交接登记请求：交车与还车共用。油量表读数与车损照片为必填。
 */
public class HandoverRequest {

    @NotNull(message = "油量表读数不能为空")
    @Min(value = 0, message = "油量表读数必须在 0-100 之间")
    @Max(value = 100, message = "油量表读数必须在 0-100 之间")
    private Integer fuelReading;

    @NotBlank(message = "车损照片不能为空")
    @Size(max = 1000, message = "车损照片信息过长")
    private String damagePhotos;

    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;

    public Integer getFuelReading() { return fuelReading; }
    public void setFuelReading(Integer fuelReading) { this.fuelReading = fuelReading; }
    public String getDamagePhotos() { return damagePhotos; }
    public void setDamagePhotos(String damagePhotos) { this.damagePhotos = damagePhotos; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
