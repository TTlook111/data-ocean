import tseslint from 'typescript-eslint';
import hooks from 'eslint-plugin-react-hooks';

/**
 * 统一 TypeScript 与 React Hook 的静态检查规则。
 */
export default tseslint.config(
  // 构建产物和第三方依赖不参与源码检查。
  {
    // 排除生成目录，避免检查工具生成内容。
    ignores: ['dist', 'node_modules'],
  },
  ...tseslint.configs.recommended,
  // React 与 TypeScript 的项目规则只作用于正式源码。
  {
    // 覆盖正式前端的 TypeScript 和 TSX 文件。
    files: ['src/**/*.{ts,tsx}'],

    // 注册 React Hook 检查插件。
    plugins: {
      // 提供 Hook 调用及依赖检查规则。
      'react-hooks': hooks,
    },

    // 明确 Hook 约束及有意保留的未使用参数约定。
    rules: {
      // 禁止在不合法的位置调用 Hook。
      'react-hooks/rules-of-hooks': 'error',
      // 检查 Hook 依赖列表是否完整。
      'react-hooks/exhaustive-deps': 'error',
      // 未使用变量报错，以下划线开头的参数允许保留。
      '@typescript-eslint/no-unused-vars': [
        'error',
        // 使用参数命名表达有意忽略，避免关闭整个检查规则。
        {
          // 仅豁免以下划线开头的参数。
          argsIgnorePattern: '^_',
        },
      ],
    },
  },
);
