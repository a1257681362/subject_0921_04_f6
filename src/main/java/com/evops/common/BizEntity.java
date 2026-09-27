package com.evops.common;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

/**
 * 业务表实体基类：每条写入都带 操作人、业务时间、请求号、版本号。
 * 版本号由 MyBatis-Plus 乐观锁插件在更新时自增。
 */
public class BizEntity extends BaseEntity {

    /** 操作人（来自请求头 X-Operator）；行被再次写入时刷新为最近操作人 */
    @com.baomidou.mybatisplus.annotation.TableField(fill = FieldFill.INSERT_UPDATE)
    private String operator;

    /** 业务时间（来自请求头 X-Biz-Time，缺省取服务端当前时间）；落行后保持不变 */
    @com.baomidou.mybatisplus.annotation.TableField(fill = FieldFill.INSERT)
    private LocalDateTime bizTime;

    /** 请求号（来自请求头 X-Request-No，入口幂等键）；行被再次写入时刷新 */
    @com.baomidou.mybatisplus.annotation.TableField(fill = FieldFill.INSERT_UPDATE)
    private String requestNo;

    /** 版本号（乐观锁，更新时自动 +1 并参与 WHERE 条件） */
    @Version
    private Integer version = 0;

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
