package com.evops.gray;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BizEntity;

/**
 * 灰度规则：scene + branch_code 唯一；branch_code = '*' 表示全量放开。
 * 试点期只放单个网点，观察期满置 '*' ON 推全，运行时生效、无需重启。
 */
@TableName("t_gray_rule")
public class GrayRule extends BizEntity {

    /** 场景：网点树调度查询 */
    public static final String SCENE_BRANCH_TREE = "BRANCH_TREE";

    /** 场景：租车单客户姓名拼音检索 */
    public static final String SCENE_CUSTOMER_NAME_SEARCH = "CUSTOMER_NAME_SEARCH";

    /** branch_code 取值：全量放开 */
    public static final String ALL_BRANCHES = "*";

    public static final String STATUS_ON = "ON";
    public static final String STATUS_OFF = "OFF";

    /** 场景标识，本期固定 BRANCH_TREE */
    private String scene;

    /** 网点编码；'*' = 全量 */
    private String branchCode;

    /** ON 生效 / OFF 停用 */
    private String status;

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getBranchCode() { return branchCode; }
    public void setBranchCode(String branchCode) { this.branchCode = branchCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
