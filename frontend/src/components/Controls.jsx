import { useId } from 'react';

/** "Dim the lights"-style switch: a real checkbox with role="switch". */
export function Switch({ label, checked, onChange }) {
  return (
    <label className="switch">
      <input
        type="checkbox"
        role="switch"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className="switch__track" aria-hidden="true" />
      <span className="switch__label">{label}</span>
    </label>
  );
}

/** A button group where each option is a toggle with aria-pressed. */
export function Segmented({ label, options, value, onChange }) {
  const id = useId();
  return (
    <div className="field">
      <span id={id} className="field__label">
        {label}
      </span>
      <div role="group" aria-labelledby={id} className="segmented">
        {options.map((o) => (
          <button
            key={o.value}
            type="button"
            className="segmented__btn"
            aria-pressed={value === o.value}
            onClick={() => onChange(o.value)}
          >
            {o.label}
          </button>
        ))}
      </div>
    </div>
  );
}

/** A labelled <select>. When `inertText` is set it renders the dashed inert state instead. */
// `{children}` stays in the same slot (after the control) in both branches: FilmDetail's "Fix…" button relies on
// React keeping it mounted when the Subtitles field flips between them, so the focus its dialog returns is not lost.
export function Picker({ label, value, onChange, options, inertText, children }) {
  const id = useId();
  if (inertText) {
    return (
      <div className="field">
        <span id={id} className="field__label">
          {label}
        </span>
        <div className="picker picker--inert" aria-labelledby={id} role="group">
          {inertText}
        </div>
        {children}
      </div>
    );
  }
  return (
    <div className="field">
      <label htmlFor={id} className="field__label">
        {label}
      </label>
      <select id={id} className="picker" value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => (
          <option key={o.value} value={o.value} disabled={o.disabled}>
            {o.label}
          </option>
        ))}
      </select>
      {children}
    </div>
  );
}
