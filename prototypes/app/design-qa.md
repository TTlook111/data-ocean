# 问数工作台原型验证

参照[方向 1](../visual-directions/direction-1.png)核对桌面对话布局，参照[方向 3](../visual-directions/direction-3.png)核对展开后的日明细。使用城南店上周牛奶净销量示例，桌面视口为 1440 × 900，完整对照截图均为 2160 × 1350。窄屏检查使用 390 × 844。

首轮在实际页面发现趋势图标签折行、窄屏面包屑换行。已修正数字和日期标签宽度及窄屏标题布局。复查时，七个数据点和日期均可读，展开明细可直接核对合计，再收起即可继续对话。

已在 ego-lite 操作新建问数、点选口径、自由补充口径和分组、纠正、换到库存、回看会话、展开明细、查看依据、导出与评价。示例日记录合计为售出 1,200 盒、退货 80 盒、净售出 1,120 盒。演示使用本地规则和样例数值。

截图存于 `output/playwright/workbench-interactive/`。全页比较见 `visual-comparison.jpg`，可核对 `desktop.png`、`expanded.png`、`clarification.png` 和 `mobile-details.png` 的交互状态。

final result: passed
