(function () {
  const sidebarItems = [
    {
      title: '系统配置',
      items: [
        { key: 'dashboard', href: 'b_end_dashboard.html', icon: 'fa-database', label: '数据源与知识库' },
        { key: 'roles', href: 'b_end_roles.html', icon: 'fa-shield-alt', label: '权限与角色' },
        { key: 'prompt', href: 'b_end_prompt.html', icon: 'fa-code', label: 'Prompt 模板' }
      ]
    },
    {
      title: '治理引擎',
      items: [{ key: 'governance', href: 'b_end_governance.html', icon: 'fa-tags', label: 'Tag 与血缘治理' }]
    },
    {
      title: '审计与监控',
      items: [
        { key: 'audit', href: 'b_end_audit.html', icon: 'fa-history', label: '查询审计日志' },
        { key: 'cost', href: 'b_end_cost.html', icon: 'fa-chart-line', label: '成本监控' }
      ]
    }
  ];

  function linkClass(active) {
    if (active) {
      return 'flex items-center gap-3 px-3 py-2.5 bg-sky-500/10 text-sky-400 rounded-lg text-sm font-medium border border-sky-500/20';
    }
    return 'flex items-center gap-3 px-3 py-2.5 text-slate-400 hover:text-slate-200 hover:bg-slate-800 rounded-lg text-sm transition-colors';
  }

  function renderSidebar(activeKey) {
    const nav = sidebarItems
      .map((group) => {
        const links = group.items
          .map((item) => {
            const active = item.key === activeKey;
            return `<a href="${item.href}" class="${linkClass(active)}"><i class="fas ${item.icon} w-5 text-center"></i> ${item.label}</a>`;
          })
          .join('');

        return `<div class="text-xs font-semibold text-slate-500 uppercase tracking-wider mb-3 px-2">${group.title}</div><nav class="space-y-1 ${group.title === '审计与监控' ? '' : 'mb-8'}">${links}</nav>`;
      })
      .join('');

    return `<aside class="w-64 bg-slate-900 text-slate-300 flex flex-col z-20 shadow-xl hidden md:flex flex-shrink-0"><div class="h-16 flex items-center justify-center gap-3 border-b border-slate-800"><div class="w-8 h-8 rounded-lg bg-sky-500 flex items-center justify-center text-white font-bold"><i class="fas fa-server"></i></div><h1 class="font-bold text-lg text-white tracking-tight">管理工作台</h1></div><div class="p-4 flex-1 overflow-y-auto">${nav}</div><div class="p-4 border-t border-slate-800 flex items-center gap-3 cursor-pointer hover:bg-slate-800 transition-colors"><img src="https://api.dicebear.com/7.x/avataaars/svg?seed=Admin" class="w-8 h-8 rounded-full bg-slate-700"><div class="flex-1 min-w-0"><p class="text-sm font-medium text-white">Admin</p><p class="text-xs text-slate-500">超级管理员</p></div><i class="fas fa-sign-out-alt text-slate-500 hover:text-red-400"></i></div></aside>`;
  }

  function mount() {
    if (document.querySelector('aside')) return;

    const main = document.querySelector('main');
    if (!main) return;

    const activeKey = document.body.dataset.adminPage || '';
    main.insertAdjacentHTML('beforebegin', renderSidebar(activeKey));
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount);
  } else {
    mount();
  }
})();

