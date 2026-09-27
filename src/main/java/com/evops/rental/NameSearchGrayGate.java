package com.evops.rental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.gray.GrayRule;
import com.evops.gray.GrayRuleMapper;
import org.springframework.stereotype.Component;

/**
 * 客户姓名拼音检索灰度闸（全局，无网点维度）。
 *
 * 仅当 scene=CUSTOMER_NAME_SEARCH 且 branch_code='*'、status=ON 的规则存在时放行。
 * 默认无规则即关闭：读路径走原订单号/业务时间检索（customerName 入参被忽略），
 * 出问题把规则置 OFF 即立刻回退、无需重启，满足上线日十分钟内回退。
 */
@Component
public class NameSearchGrayGate {

    private final GrayRuleMapper grayRuleMapper;

    public NameSearchGrayGate(GrayRuleMapper grayRuleMapper) {
        this.grayRuleMapper = grayRuleMapper;
    }

    public boolean isOpen() {
        Long count = grayRuleMapper.selectCount(new LambdaQueryWrapper<GrayRule>()
                .eq(GrayRule::getScene, GrayRule.SCENE_CUSTOMER_NAME_SEARCH)
                .eq(GrayRule::getBranchCode, GrayRule.ALL_BRANCHES)
                .eq(GrayRule::getStatus, GrayRule.STATUS_ON));
        return count != null && count > 0;
    }
}
