package com.evops.rental.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class OrderCreateRequest {

    /** 订单号；不传则由系统生成。库层有唯一约束兜底。 */
    @Size(max = 40, message = "订单号长度不能超过 40")
    private String orderNo;

    @NotNull(message = "车辆ID不能为空")
    private Long vehicleId;

    @NotBlank(message = "客户姓名不能为空")
    @Size(max = 64, message = "客户姓名长度不能超过 64")
    private String customerName;

    /** 归属网点编码（可选；recon-1 对账按网点抽取内部流水） */
    @Size(max = 32, message = "网点编码长度不能超过 32")
    private String branchCode;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
}
