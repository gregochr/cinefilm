// URL state of the phone search sheet. No JSX and no DOM: the sheet is open while the current route carries
// `search=1`, so the Back gesture closes it. `searchFrom` in `location.state` marks an entry the Search tab pushed in
// this session, i.e. one there is an in-app page behind to go back to.
export const SHEET_PARAM = 'search';

/** Whether a `location.search` string asks for the sheet. */
export const isSheetOpen = (search) => new URLSearchParams(search).get(SHEET_PARAM) === '1';

/** The current location plus `search=1`, other params and the hash kept. */
export function sheetTo({ pathname, search, hash }) {
  const params = new URLSearchParams(search);
  params.set(SHEET_PARAM, '1');
  return { pathname, search: `?${params.toString()}`, hash };
}

/** The current location without the `search` param. */
export function withoutSheet({ pathname, search, hash }) {
  const params = new URLSearchParams(search);
  params.delete(SHEET_PARAM);
  const rest = params.toString();
  return { pathname, search: rest ? `?${rest}` : '', hash };
}

/** The state the Search tab pushes with, so Cancel can tell "go back" from "drop the param". */
export const SHEET_STATE = { searchFrom: true };
export const openedFromApp = (state) => state?.searchFrom === true;
