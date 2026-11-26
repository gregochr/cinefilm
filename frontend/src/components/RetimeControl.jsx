import { retimeTool } from '../state/subtitleFix.js';
import { fixRetime as copy } from '../state/subtitleFixCopy.js';
import { ActionWithHint } from './ActionWithHint.jsx';

/**
 * A download's "Don't retime" / "Retime" (frame-rate plan, 4b): which one the row's own facts say (`retimeTool`), or
 * nothing. A press sends the choice for that one row; the row then shows the server's answer. While any action runs
 * (`busy`) it is `aria-disabled`, not `disabled`, like the other file tools: a real `disabled` drops the pressed
 * button's focus to the page, and a failed press would leave the viewer nowhere. A press while busy does nothing.
 */
export default function RetimeControl({ item, name, busy, actions }) {
  const tool = retimeTool(item);
  if (!tool) return null;
  const { label } = copy[tool];
  const hand = item.offsetSource === 'OWNER' || item.verified === true;
  const hint = tool === 'retime' && hand ? copy.retimeResets : copy[tool].hint;
  return (
    <ActionWithHint
      layout="line"
      hint={hint}
      aria-label={copy.spoken(label, name)}
      aria-disabled={busy || undefined}
      onClick={() => {
        if (busy) return;
        actions.retime(item.id, tool === 'retime');
      }}
    >
      {label}
    </ActionWithHint>
  );
}
