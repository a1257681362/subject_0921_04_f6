package com.evops.recon.dto;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 对账配置变更申请：白天只登记 PENDING 变更单，夜间批处理统一生效。
 * configKey 当前仅支持 MATCH_TOLERANCE（匹配容差，含边界，单位元，不能为负）。
 */
public class ConfigChangeRequest {

    @NotBlank(message = "配置项不能为空")
    @Size(max = 32, message = "配置项长度不能超过 32")
    private String configKey;

    @NotNull(message = "新值不能为空")
    @DecimalMin(value = "0.00", message = "匹配容差不能为负")
    @Digits(integer = 10, fraction = 2, message = "匹配容差整数位最多 10 位、小数最多 2 位")
    private BigDecimal newValue;

    @Size(max = 500, message = "变更原因长度不能超过 500")
    private String reason;

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public BigDecimal getNewValue() { return newValue; }
    public void setNewValue(BigDecimal newValue) { this.newValue = newValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
