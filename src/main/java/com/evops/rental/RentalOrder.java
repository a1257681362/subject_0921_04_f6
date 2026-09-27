package com.evops.rental;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

import java.math.BigDecimal;

/**
 * 租车单。全量记录、不做物理删除。
 * 状态机：CREATED -> RENTING -> SETTLED -> ARCHIVED；SETTLED/ARCHIVED 可冲销为 REVERSED。
 */
@TableName("t_rental_order")
public class RentalOrder extends BizEntity {

    /** 订单号（库层唯一约束兜底） */
    private String orderNo;

    private Long vehicleId;

    private String customerName;

    /** 归属网点编码（可空=未分派；recon-1 对账按网点抽取内部流水） */
    private String branchCode;

    /** CREATED / RENTING / SETTLED / ARCHIVED / REVERSED */
    private String status;

    private BigDecimal settleAmount;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getSettleAmount() { return settleAmount; }
    public void setSettleAmount(BigDecimal settleAmount) { this.settleAmount = settleAmount; }
}
