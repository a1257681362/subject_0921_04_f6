package com.evops.rental.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 客户改名请求。改名与拼音索引同步、RENAME 事件在同一事务内完成。 */
public class RenameCustomerRequest {

    @NotBlank(message = "客户姓名不能为空")
    @Size(max = 64, message = "客户姓名长度不能超过 64")
    private String customerName;

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
}
