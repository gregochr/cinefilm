import { useRef, useState } from 'react';
import FilmRow from '../../components/FilmRow.jsx';
import { Poster } from '../../components/Poster.jsx';
import {
  PREVIEW_CAPTION,
  PREVIEW_LABEL,
  PREVIEW_TITLE,
  fallbackText,
  previewRow,
} from '../../state/christmas.js';
import { readPreviewOpen, writePreviewOpen } from '../../state/christmasPreview.js';
import ChristmasPreviewSheet from './ChristmasPreviewSheet.jsx';

/** Home's "This week" row as Christmas week would fill it, or the sentence that says it falls back. */
function PreviewRow({ data, phone }) {
  const { fallsBack, min, films } = previewRow(data);
  if (fallsBack) return <p className="xmas-preview__fallback">{fallbackText(min)}</p>;
  return (
    <FilmRow title={PREVIEW_TITLE} sub={PREVIEW_CAPTION} variant={phone ? 'posters' : 'christmas'}>
      {films.map((film) => (
        <Poster key={film.id} film={film} />
      ))}
    </FilmRow>
  );
}

/**
 * The preview inside the status card. Desktop: the label, a Hide/Show button (remembered in this browser) and the row.
 * Phone: a full-width button that opens the row in a sheet (`sheetOpen` is the screen's, so it can keep the toast
 * reachable while the sheet is up; `toast` is rendered inside the sheet then).
 */
export default function ChristmasPreview({ data, phone, sheetOpen, onSheetOpenChange, toast }) {
  const [open, setOpen] = useState(readPreviewOpen);
  const opener = useRef(null);

  if (phone) {
    const closeSheet = () => {
      onSheetOpenChange(false);
      opener.current?.focus();
    };
    return (
      <div className="xmas-preview xmas-preview--phone">
        <button
          ref={opener}
          type="button"
          className="xmas-preview__open"
          aria-haspopup="dialog"
          onClick={() => onSheetOpenChange(true)}
        >
          <span>Preview Home in Christmas week</span>
          <span aria-hidden="true">›</span>
        </button>
        {sheetOpen ? (
          <ChristmasPreviewSheet onClose={closeSheet} toast={toast}>
            <PreviewRow data={data} phone />
          </ChristmasPreviewSheet>
        ) : null}
      </div>
    );
  }

  const toggle = () => {
    writePreviewOpen(!open);
    setOpen(!open);
  };
  return (
    <div className="xmas-preview">
      <div className="xmas-preview__head">
        <span className="xmas-preview__label">{PREVIEW_LABEL}</span>
        <button
          type="button"
          className="xmas-preview__toggle"
          aria-expanded={open}
          onClick={toggle}
        >
          {open ? 'Hide preview' : 'Show preview'}
        </button>
      </div>
      {open ? <PreviewRow data={data} phone={false} /> : null}
    </div>
  );
}
