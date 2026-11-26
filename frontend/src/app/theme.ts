import type { ThemeConfig } from 'antd';

/**
 * 将已确认的后台尺度落实到组件主题，避免默认算法在不同组件间产生颜色与字号偏差。
 */
export const appTheme: ThemeConfig = {
  // 基础色、字体和控件高度与原型共用一套规则。
  token: {
    /** 当前主要业务动作及记录查看入口。 */
    colorPrimary: '#2f63d5',
    /** 主操作悬停反馈。 */
    colorPrimaryHover: '#244eaa',
    /** 主操作按下反馈。 */
    colorPrimaryActive: '#244eaa',
    /** 已选项目的浅底色。 */
    colorPrimaryBg: '#eaf0fc',
    /** 记录链接颜色。 */
    colorLink: '#2f63d5',
    /** 链接悬停颜色。 */
    colorLinkHover: '#244eaa',
    /** 正文颜色。 */
    colorText: '#1f2937',
    /** 标题不使用组件库默认纯黑。 */
    colorTextHeading: '#1f2937',
    /** 辅助信息颜色。 */
    colorTextSecondary: '#5b6575',
    /** 占位文字仍保持可辨识的对比度。 */
    colorTextPlaceholder: '#5b6575',
    /** 未接入入口及不可用控件。 */
    colorTextDisabled: '#929aa6',
    /** 工作区底色。 */
    colorBgLayout: '#f5f7fa',
    /** 面板与输入控件底色。 */
    colorBgContainer: '#ffffff',
    /** 控件边界。 */
    colorBorder: '#e5e7eb',
    /** 面板、表格与章节分隔。 */
    colorBorderSecondary: '#e5e7eb',
    /** 浮层与章节分隔。 */
    colorSplit: '#e5e7eb',
    /** 正常状态文字。 */
    colorSuccess: '#216449',
    /** 正常状态浅底色。 */
    colorSuccessBg: '#e8f4ee',
    /** 正常状态边框不重复强调。 */
    colorSuccessBorder: '#e8f4ee',
    /** 正常状态标签文字。 */
    colorSuccessText: '#216449',
    /** 危险操作使用清楚的深红文字与按钮底色。 */
    colorError: '#b43c3c',
    /** 危险按钮悬停继续保持白字对比度。 */
    colorErrorHover: '#9f3030',
    /** 危险按钮按下反馈。 */
    colorErrorActive: '#862727',
    /** 失败反馈采用克制浅底。 */
    colorErrorBg: '#fdf0f0',
    /** 失败提示边框。 */
    colorErrorBorder: '#f3c9c9',
    /** 错误信息文字保持可读对比度。 */
    colorErrorText: '#b43c3c',
    /** 遮罩降低干扰，沿用原型的灰蓝色。 */
    colorBgMask: '#16243a55',
    /** 正文字号。 */
    fontSize: 14,
    /** large 控件只增大点击区域，不放大界面正文。 */
    fontSizeLG: 14,
    /** 区块标题字号。 */
    fontSizeHeading4: 16,
    /** 正文行高为 22px。 */
    lineHeight: 22 / 14,
    /** 普通表单控件高度。 */
    controlHeight: 40,
    /** 查询及表单统一采用 large 的 40px 尺度。 */
    controlHeightLG: 40,
    /** 行内操作高度。 */
    controlHeightSM: 32,
    /** 输入及按钮圆角。 */
    borderRadius: 6,
    /** 面板及浮层圆角。 */
    borderRadiusLG: 8,
    /** 中文采用本机稳定字体，避免引入远程字体。 */
    fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif',
  },
  // 通过公开主题接口统一各组件，不修改组件库的交互实现。
  components: {
    // 中文双月日历使用紧凑网格，避免在较窄桌面挡住整个工作区。
    DatePicker: {
      /** 日期列保持整周均匀排布。 */
      cellWidth: 32,
      /** 日期点击区与后台紧凑控件尺度协调。 */
      cellHeight: 28,
    },
    // 抽屉标题使用 16px 区块尺度，避免跟随输入控件的 14px large 字体。
    Drawer: {
      /** 查看型详情标题与正文形成清楚的层级。 */
      fontSizeLG: 16,
    },
    // 全页和表格等待保留区块感，尺寸与紧凑的桌面布局协调。
    Spin: {
      /** 大尺寸环形指示器的直径。 */
      dotSizeLG: 28,
    },
    // 分段控件用底色定位当前筛选，焦点由组件的独立轮廓表达。
    Segmented: {
      /** 未选分段共享中性轨道。 */
      trackBg: '#f3f5f8',
      /** 选项切换后可识别的蓝色浅底。 */
      itemSelectedBg: '#eaf0fc',
      /** 当前分段的文字反差。 */
      itemSelectedColor: '#244eaa',
    },
    // 表头用连续浅底分区，消除默认的竖向短分隔线。
    Table: {
      /** 单元格上下内距。 */
      cellPaddingBlock: 12,
      /** 单元格中间列左右内距。 */
      cellPaddingInline: 16,
      /** 表头浅底。 */
      headerBg: '#f8fafc',
      /** 表头辅助文字。 */
      headerColor: '#5b6575',
      /** 表头列之间不增加短竖线。 */
      headerSplitColor: 'transparent',
      /** 鼠标所在记录的轻量反馈。 */
      rowHoverBg: '#f8fafc',
    },
    // 常驻导航保持原型的紧凑层级与选中样式。
    Menu: {
      /** 导航行高度。 */
      itemHeight: 40,
      /** 相邻导航行间距。 */
      itemMarginBlock: 2,
      /** 导航内层不重复叠加水平边距。 */
      itemMarginInline: 0,
      /** 当前页面的浅蓝底。 */
      itemSelectedBg: '#eaf0fc',
      /** 当前页面名称。 */
      itemSelectedColor: '#2f63d5',
      /** 未选菜单采用辅助文字。 */
      itemColor: '#5b6575',
      /** 普通菜单悬停浅底。 */
      itemHoverBg: '#f3f5f8',
      /** 子菜单不另起灰色背景。 */
      subMenuItemBg: 'transparent',
      /** 导航图标与原型的 16px 尺度一致。 */
      iconSize: 16,
      /** 图标到名称的间距。 */
      iconMarginInlineEnd: 10,
      /** 导航选中圆角。 */
      itemBorderRadius: 6,
    },
    // 主按钮不叠加默认投影，靠颜色和清楚的边界区分主次。
    Button: {
      /** 主操作不额外投影。 */
      primaryShadow: 'none',
      /** 次操作不额外投影。 */
      defaultShadow: 'none',
      /** 次级操作悬停保留中性色，主色留给关键动作和导航。 */
      defaultHoverBg: '#f8fafc',
      /** 轻量加深边界，让悬停反馈在白色面板上可辨认。 */
      defaultHoverBorderColor: '#c8ced8',
      /** 悬停时保持次级按钮的文字层级。 */
      defaultHoverColor: '#1f2937',
      /** 危险操作靠对比和文字提示，无需额外投影。 */
      dangerShadow: 'none',
      /** 40px 按钮沿用正文尺度的水平内距。 */
      paddingInlineLG: 16,
    },
    // 表单标签与控件保持原型的 8px 间隔和 16px 字段间隔。
    Form: {
      /** 纵向字段标签采用 22px 正文高度，不继承输入框的 40px 高度。 */
      labelHeight: 22,
      /** 纵向字段间距。 */
      itemMarginBottom: 16,
      /** 字段名称颜色。 */
      labelColor: '#5b6575',
      /** 标签到控件的距离。 */
      verticalLabelPadding: '0 0 8px',
    },
    // 抽屉及摘要使用相同的标签与值阅读轴。
    Descriptions: {
      /** 详情标签颜色。 */
      labelColor: '#5b6575',
      /** 标签和值之间的距离。 */
      itemPaddingBottom: 16,
    },
    // 导航树和侧栏共用当前项目的浅蓝选中状态。
    Tree: {
      /** 选中节点背景。 */
      nodeSelectedBg: '#eaf0fc',
      /** 选中节点文字。 */
      nodeSelectedColor: '#2f63d5',
      /** 组织树节点高度。 */
      titleHeight: 38,
    },
  },
};
