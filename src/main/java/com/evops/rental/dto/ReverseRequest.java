package com.evops.rental.dto;

import javax.validation.constraints.Size;

public class ReverseRequest {

    @Size(max = 500, message = "冲销原因长度不能超过 500")
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
