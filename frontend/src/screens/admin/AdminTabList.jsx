import { useRef } from 'react';
import { tabAfterKey } from '../../state/adminSubtitles.js';

const DEFAULT_PREFIX = 'admin-subtitles';

/** The ids a tab and its panel use to name each other (`prefix` tells one tab list from another). */
export const tabId = (key, prefix = DEFAULT_PREFIX) => `${prefix}-tab-${key}`;
export const panelId = (key, prefix = DEFAULT_PREFIX) => `${prefix}-panel-${key}`;

/**
 * A real `tablist` for panels that switch in place (unlike the section nav, which changes address). `tabs` are
 * `{ key, label, count }` (`count` null until it is known); `selected` is the open tab's key. The selected tab alone is
 * in the tab order (roving tabindex); ArrowLeft and ArrowRight move between tabs and wrap, Home and End jump to the
 * first and last, and the tab that is moved to is selected and focused at once. `idPrefix` is the one `tabId` and
 * `panelId` are given for this list's panels.
 */
export default function AdminTabList({
  label,
  tabs,
  selected,
  onSelect,
  idPrefix = DEFAULT_PREFIX,
}) {
  const refs = useRef({});
  const keys = tabs.map((tab) => tab.key);
  const onKeyDown = (event) => {
    const next = tabAfterKey(keys, selected, event.key);
    if (next === null) return;
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
          id={tabId(tab.key, idPrefix)}
          aria-selected={selected === tab.key}
          aria-controls={panelId(tab.key, idPrefix)}
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
