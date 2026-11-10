import js from '@eslint/js';
import globals from 'globals';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import prettier from 'eslint-config-prettier';

export default [
  { ignores: ['dist/', 'coverage/', 'node_modules/', 'reports/', '.stryker-tmp/'] },
  js.configs.recommended,
  react.configs.flat.recommended,
  react.configs.flat['jsx-runtime'],
  jsxA11y.flatConfigs.recommended,
  {
    files: ['**/*.{js,jsx}'],
    plugins: { 'react-hooks': reactHooks },
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser },
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    settings: { react: { version: 'detect' } },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      'react/prop-types': 'off',
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
  {
    files: ['*.config.{js,mjs}', 'scripts/**/*.mjs'],
    languageOptions: { globals: { ...globals.node } },
  },
  {
    files: ['**/*.test.{js,jsx}', 'src/test/**'],
    languageOptions: { globals: { ...globals.vitest, ...globals.node } },
  },
  {
    // Banned test patterns (docs/engineering/test-standards.md: "Queries", "userEvent", "Keyboard tests").
    files: ['**/*.test.{js,jsx}', 'src/test/**'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: 'CallExpression[callee.property.name=/^querySelector(All)?$/]',
          message:
            'No querySelector in tests: query by role, or add a role or data-testid (test-standards.md, "Queries").',
        },
        {
          selector:
            'MemberExpression[property.name=/^(parentElement|firstChild|lastChild|children)$/]',
          message:
            'No DOM walking in tests (parentElement, firstChild, lastChild, children): query by role or data-testid (test-standards.md, "Queries").',
        },
        {
          selector: "CallExpression[callee.object.name='fireEvent'][callee.property.name='click']",
          message:
            'Use userEvent.click, not fireEvent.click (test-standards.md, "userEvent is the default").',
        },
        {
          selector:
            "CallExpression[callee.object.name='fireEvent'][callee.property.name='keyDown']:not([arguments.0.object.name='document'][arguments.0.property.name='activeElement'])",
          message:
            'Send keys where the browser would: userEvent.keyboard, or on a frozen fake clock fireEvent.keyDown(document.activeElement, ...) (test-standards.md, "Keyboard tests").',
        },
      ],
    },
  },
  prettier,
];
