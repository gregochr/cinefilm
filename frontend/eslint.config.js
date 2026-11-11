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
    // Banned test patterns (docs/engineering/test-standards.md: "Queries", "userEvent", "Keyboard tests",
    // "A table both suites run").
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
        {
          // The two forms that broke the Stryker dry run: an import, or a path string, that climbs out of frontend/.
          selector: 'Literal[value=/^(\\.\\.\\/)+(backend|docs)\\//]',
          message:
            'No relative path out of frontend/ in tests: load the file with readRepoJson (src/test/repoFile.js), or the Stryker run fails (test-standards.md, "A table both suites run").',
        },
        {
          selector:
            // A skip/only/todo/skipIf/runIf member anywhere in a chain rooted at it/test/describe: it.skip,
            // test.concurrent.skip, test.each(rows).skip, describe.concurrent.only, test.skipIf(c)(...), test.runIf(c)(...).
            'MemberExpression[property.name=/^(skip|only|todo|skipIf|runIf)$/][object.name=/^(it|test|describe)$/], MemberExpression[property.name=/^(skip|only|todo|skipIf|runIf)$/][object.object.name=/^(it|test|describe)$/], MemberExpression[property.name=/^(skip|only|todo|skipIf|runIf)$/][object.callee.object.name=/^(it|test|describe)$/], MemberExpression[property.name=/^(skip|only|todo|skipIf|runIf)$/][object.callee.object.object.name=/^(it|test|describe)$/]',
          message:
            'No it/test/describe .skip, .only, .todo, .skipIf or .runIf (conditional skips included): fix the test or delete it (test-standards.md, Frontend "Do not" list).',
        },
        {
          selector: 'CallExpression[callee.name=/^(xit|xtest|xdescribe|fit|fdescribe)$/]',
          message:
            'No xit, xtest, xdescribe, fit or fdescribe: fix the test or delete it (test-standards.md, Frontend "Do not" list).',
        },
        {
          selector: 'CallExpression[callee.property.name=/^toBeTruthy$/]',
          message:
            'No toBeTruthy(): assert the value (toHaveAttribute, toHaveTextContent, toBe(...)); getBy* already throws and queryBy* passes for the wrong element (test-standards.md, "Assertions").',
        },
        {
          selector:
            'CallExpression[callee.object.name=/^vi$/][callee.property.name=/^(mock|doMock)$/][arguments.0.value=/(^|\\/)api(\\/|$)/]',
          message:
            'Do not vi.mock anything under src/api: stub fetch with installFetch (src/test/mockApi.jsx) so the API client stays under test (test-standards.md, "CineFilm conventions").',
        },
        {
          selector:
            'CallExpression[callee.property.name=/^(toMatchSnapshot|toMatchInlineSnapshot|toMatchFileSnapshot)$/]',
          message:
            'No snapshots: assert the values the handover promises (test-standards.md, "Assertions").',
        },
        {
          // The sleep form: a setTimeout handed the promise executor's own resolver.
          selector:
            "NewExpression[callee.name='Promise'] CallExpression[callee.name='setTimeout'][arguments.0.type='Identifier']",
          message:
            'No sleeps in tests (await new Promise(r => setTimeout(r, n))): wait for the UI with findBy* or vi.waitFor, or drive a fake clock (test-standards.md, "No sleeps").',
        },
        {
          selector: 'MemberExpression[property.name=/^(className|classList)$/]',
          message:
            'No class-name reads in tests: query by role or data-testid (test-standards.md, "Queries").',
        },
        {
          selector: 'CallExpression[callee.property.name=/^getElementsBy(ClassName|TagName)$/]',
          message:
            'No getElementsByClassName or getElementsByTagName in tests: query by role or data-testid (test-standards.md, "Queries").',
        },
      ],
    },
  },
  prettier,
];
