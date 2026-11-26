// 历史引用仅由需要它的测试显式建立，不作为运行页面的默认事实。
module.exports = data => {
  data.noteUses.push(
    { id: 'CG-021', noteId: 'ZL-015', revision: 1, kind: '治理成果', name: '销售额与退货扣减口径', state: '已确认', actor: 'u12', at: '2026-11-11 11:20:00', method: '人工治理依据', purpose: '测试历史人工引用。', excerpt: '销售额按实际收款计算，退货需要扣除。作废单据不计入销售。', result: '测试销售额口径。', reviewer: 'u11', reviewedAt: '2026-11-11 14:10:00' },
    { id: 'RW-008', noteId: 'ZL-014', revision: 1, kind: '治理任务', name: '库存与盘点规则核对', state: '进行中', actor: 'u11', at: '2026-11-11 11:30:00', method: 'AI 治理输入', purpose: '测试在途引用。', result: '测试未完成产出。' },
    { id: 'RW-006', noteId: 'ZL-013', revision: 1, kind: '治理任务', name: '促销折扣规则识别', state: '已完成', actor: 'u12', at: '2026-11-09 11:00:00', method: 'AI 治理输入', purpose: '测试历史任务引用。', result: '测试待确认建议。', ended: '2026-11-09 11:08:00' },
    { id: 'CG-009', noteId: 'ZL-001', revision: 1, kind: '治理成果', name: '历史订单归属说明', state: '已确认', actor: 'u11', at: '2026-11-04 09:00:00', method: '人工治理依据', purpose: '测试撤下资料的历史引用。', result: '测试历史定义。', reviewer: 'u11', reviewedAt: '2026-11-04 09:30:00' }
  );
};
