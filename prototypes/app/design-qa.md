# 问数工作台原型验证

参照[方向 1](../visual-directions/direction-1.png)核对桌面对话布局，参照[方向 3](../visual-directions/direction-3.png)核对展开后的日明细。使用城南店上周牛奶净销量示例，桌面视口为 1440 × 900，完整对照截图均为 2160 × 1350。窄屏检查使用 390 × 844。

首轮在实际页面发现趋势图标签折行、窄屏面包屑换行。已修正数字和日期标签宽度及窄屏标题布局。复查时，七个数据点和日期均可读，展开明细可直接核对合计，再收起即可继续对话。

已在 ego-lite 操作新建问数、点选口径、自由补充口径和分组、纠正、换到库存、回看会话、展开明细、查看依据、导出与评价。示例日记录合计为售出 1,200 盒、退货 80 盒、净售出 1,120 盒。演示使用本地规则和样例数值。

截图存于 `output/playwright/workbench-interactive/`。全页比较见 `visual-comparison.jpg`，可核对 `desktop.png`、`expanded.png`、`clarification.png` 和 `mobile-details.png` 的交互状态。

## 店长追问复测

在 ego-lite 新建问数，用“扣掉是什么意思？”追问口径后，两个选择仍可点选。点“按售出盒数算”和直接输入“按卖出去的算，按天列”均得到上周售出 1,200 盒。

回复“扣掉退货，只看周末两天”得到 382 盒，展开可核对周六 196 盒、周日 186 盒。接着把上周改成本周，仍看周末时为 277 盒。换题查库存后，再展开旧回答仍显示当次查询的日期和数值。390 × 844 窄屏下，两行明细和输入框均在视口内。

“今天的酸奶库存”得到 96 盒，接问“那它上周的净销量呢？”会说明缺少酸奶销量记录。指定酸奶和豆奶得到两行库存，合计 168 盒。

修复前的操作记录在 `output/playwright/workbench-manager-audit/`，复测截图在 `output/playwright/workbench-manager-fix/`。对话规则可运行 `node --test prototypes/app/tests/dialogue.test.cjs` 复查。
