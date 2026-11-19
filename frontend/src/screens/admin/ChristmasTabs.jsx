import { useRef } from 'react';
import { TABS } from '../../state/christmas.js';
import { tabAfterKey } from '../../state/adminSubtitles.js';

export const tabDomId = (key) => `xmas-tab-${key}`;
export const panelDomId = (key) => `xmas-panel-${key}`;

/**
 * The three lists as a segmented `tablist`. Only the selected tab is in the tab order; the arrow keys, Home and End
 * move to a tab and select it. `counts` is `{ inWeek, added, leftOut }`. The phone shows the short labels, the name
 * read aloud is always the long one with its count.
 */
export default function ChristmasTabs({ counts, selected, onSelect }) {
  const refs = useRef({});
  const keys = TABS.map((tab) => tab.key);
  const onKeyDown = (event) => {
    const next = tabAfterKey(keys, selected, event.key);
    if (next === null) return;
    event.preventDefault();
    onSelect(next);
    refs.current[next]?.focus();
  };
  return (
    <div className="xmas-tabs" role="tablist" aria-label="Christmas lists">
      {TABS.map((tab) => (
        <button
          key={tab.key}
          ref={(node) => {
            refs.current[tab.key] = node;
          }}
          type="button"
          role="tab"
          id={tabDomId(tab.key)}
          aria-selected={selected === tab.key}
          aria-controls={panelDomId(tab.key)}
          aria-label={`${tab.label}, ${counts[tab.key]}`}
          tabIndex={selected === tab.key ? 0 : -1}
          className="xmas-tab"
          onClick={() => onSelect(tab.key)}
          onKeyDown={onKeyDown}
        >
          <span className="xmas-tab__label" aria-hidden="true">
            <span className="xmas-tab__long">{tab.label}</span>
            <span className="xmas-tab__short">{tab.short}</span>
          </span>
          <span className="xmas-tab__count" aria-hidden="true">
            {counts[tab.key]}
          </span>
        </button>
      ))}
    </div>
  );
}
