package com.evops.rental;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 状态机规则：订单合法流转路径，集中定义、配置驱动。
 * (from_status, action) 唯一；新增状态/路径只插规则行，不改代码。
 * 保留动作（PICKUP/RETURN/ARCHIVE/REVERSE）由专用接口承载业务规则，
 * open_api=OFF，通用流转入口不可执行；内置主流程路径 built_in=ON，配置接口禁改。
 */
@TableName("t_rental_transition_rule")
public class TransitionRule extends BizEntity {

    /** 源状态 */
    private String fromStatus;

    /** 流转动作：PICKUP/RETURN/ARCHIVE/REVERSE/自定义 */
    private String action;

    /** 目标状态 */
    private String toStatus;

    /** ON 通用流转入口可执行 / OFF 仅专用业务接口 */
    private String openApi;

    /** ON 生效 / OFF 停用 */
    private String enabled;

    /** ON 内置主流程路径（种子），配置接口禁改 */
    private String builtIn;

    private String remark;

    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getOpenApi() { return openApi; }
    public void setOpenApi(String openApi) { this.openApi = openApi; }
    public String getEnabled() { return enabled; }
    public void setEnabled(String enabled) { this.enabled = enabled; }
    public String getBuiltIn() { return builtIn; }
    public void setBuiltIn(String builtIn) { this.builtIn = builtIn; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
