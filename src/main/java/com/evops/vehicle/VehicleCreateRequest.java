package com.evops.vehicle;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class VehicleCreateRequest {

    @NotBlank(message = "车牌号不能为空")
    @Size(max = 32, message = "车牌号长度不能超过 32")
    private String plateNo;

    @Size(max = 64, message = "车型长度不能超过 64")
    private String model;

    public String getPlateNo() { return plateNo; }
    public void setPlateNo(String plateNo) { this.plateNo = plateNo; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
