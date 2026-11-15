import { useRef } from 'react';
import { tabAfterKey } from '../../state/adminSubtitles.js';

/** The ids a tab and its panel use to name each other. */
export const tabId = (key) => `admin-subtitles-tab-${key}`;
export const panelId = (key) => `admin-subtitles-panel-${key}`;

/**
 * A real `tablist` for panels that switch in place (unlike the section nav, which changes address). `tabs` are
 * `{ key, label, count }` (`count` null until it is known); `selected` is the open tab's key. The selected tab alone is
 * in the tab order (roving tabindex); ArrowLeft and ArrowRight move between tabs and wrap, Home and End jump to the
 * first and last, and the tab that is moved to is selected and focused at once.
 */
export default function AdminTabList({ label, tabs, selected, onSelect }) {
  const refs = useRef({});
  const keys = tabs.map((tab) => tab.key);
  const onKeyDown = (event) => {
    const next = tabAfterKey(selected, event.key);
    if (next === null || !keys.includes(next)) return;
    event.preventDefault();
    onSelect(next);
    refs.current[next]?.focus();
  };
  return (
    <div className="admin__tabs" role="tablist" aria-label={label}>
      {tabs.map((tab) => (
        <button
          key={tab.key}
          ref={(node) => {
            refs.current[tab.key] = node;
          }}
          type="button"
          role="tab"
          id={tabId(tab.key)}
          aria-selected={selected === tab.key}
          aria-controls={panelId(tab.key)}
          tabIndex={selected === tab.key ? 0 : -1}
          className="admin__tab"
          onClick={() => onSelect(tab.key)}
          onKeyDown={onKeyDown}
        >
          {tab.label}{' '}
          {tab.count != null ? <span className="admin__tab-count">{tab.count}</span> : null}
        </button>
      ))}
    </div>
  );
}
