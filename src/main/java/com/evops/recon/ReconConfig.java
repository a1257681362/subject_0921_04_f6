package com.evops.recon;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 当前生效的对账配置（单行 id=1）：匹配容差等。
 * 只允许夜间配置批处理改写，白天的调整走 {@link ReconConfigChange} 变更单排队。
 * 该表不随标准业务四要素基类（主键固定 INPUT 语义），审计列自管。
 */
@TableName("t_recon_config")
public class ReconConfig {

    /** 固定单行主键 */
    public static final long SINGLETON_ID = 1L;

    public static final String KEY_MATCH_TOLERANCE = "MATCH_TOLERANCE";

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 匹配容差（含边界）：双方金额差绝对值 <= 容差视为金额一致 */
    private BigDecimal matchTolerance;

    private String operator;
    private LocalDateTime bizTime;
    private String requestNo;

    @Version
    private Integer version = 0;

    private Long createBy;
    private LocalDateTime createTime;
    private Long updateBy;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BigDecimal getMatchTolerance() { return matchTolerance; }
    public void setMatchTolerance(BigDecimal matchTolerance) { this.matchTolerance = matchTolerance; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Long getUpdateBy() { return updateBy; }
    public void setUpdateBy(Long updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
