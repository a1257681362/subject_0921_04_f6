package com.evops.branch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizException;
import com.evops.gray.GrayRule;
import com.evops.gray.GrayRuleMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网点树灰度闸：调度查询新链路（children/subtree/path）按灰度规则放行。
 * 全量规则（branch_code = '*'）ON 直接放行；否则节点落在任一生效试点网点的
 * 子树内（含试点自身，经闭包表判定）放行，其余拒绝。
 * 运维写路径（建点/迁移/排序）不过闸。
 */
@Component
public class BranchGrayGate {

    private final GrayRuleMapper grayRuleMapper;
    private final BranchMapper branchMapper;
    private final BranchClosureMapper closureMapper;

    public BranchGrayGate(GrayRuleMapper grayRuleMapper, BranchMapper branchMapper,
                          BranchClosureMapper closureMapper) {
        this.grayRuleMapper = grayRuleMapper;
        this.branchMapper = branchMapper;
        this.closureMapper = closureMapper;
    }

    public boolean isOpen(Long nodeId) {
        Long full = grayRuleMapper.selectCount(new LambdaQueryWrapper<GrayRule>()
                .eq(GrayRule::getScene, GrayRule.SCENE_BRANCH_TREE)
                .eq(GrayRule::getBranchCode, GrayRule.ALL_BRANCHES)
                .eq(GrayRule::getStatus, GrayRule.STATUS_ON));
        if (full != null && full > 0) {
            return true;
        }
        List<GrayRule> pilots = grayRuleMapper.selectList(new LambdaQueryWrapper<GrayRule>()
                .eq(GrayRule::getScene, GrayRule.SCENE_BRANCH_TREE)
                .eq(GrayRule::getStatus, GrayRule.STATUS_ON)
                .ne(GrayRule::getBranchCode, GrayRule.ALL_BRANCHES));
        for (GrayRule rule : pilots) {
            Branch pilot = branchMapper.selectOne(new LambdaQueryWrapper<Branch>()
                    .eq(Branch::getBranchCode, rule.getBranchCode()));
            if (pilot != null && closureMapper.countLink(pilot.getId(), nodeId) > 0) {
                return true;
            }
        }
        return false;
    }

    public void assertOpen(Long nodeId) {
        if (!isOpen(nodeId)) {
            throw new BizException("网点未在灰度试点范围，新链路查询暂未开放：" + nodeId);
        }
    }
}
