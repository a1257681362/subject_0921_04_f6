package com.evops.rental;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 租车单客户姓名拼音索引，与租车单一一对应（order_id 即主键，非自增）。
 * 派生索引数据：下单/改名在同一业务事务内同步，另有全量重建与启动自愈。
 * 不随行业务四要素，相关业务动作留痕见 t_rental_order_event。
 */
@TableName("t_rental_order_name_index")
public class RentalOrderNameIndex {

    /** 对应租车单 id */
    @TableId(type = IdType.INPUT)
    private Long orderId;

    /** 冗余订单上的客户姓名原文，用于回显 */
    private String customerName;

    /** NFKC+小写+去标点后的姓名，支持中文/外文字母直查 */
    private String normalizedName;

    /** 逐字全拼、无分隔符小写，如 张三 -> zhangsan */
    private String fullPinyin;

    /** 逐字拼音首字母小写，如 张三 -> zs */
    private String initials;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getNormalizedName() { return normalizedName; }
    public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }
    public String getFullPinyin() { return fullPinyin; }
    public void setFullPinyin(String fullPinyin) { this.fullPinyin = fullPinyin; }
    public String getInitials() { return initials; }
    public void setInitials(String initials) { this.initials = initials; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
