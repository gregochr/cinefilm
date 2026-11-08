// Stable warm, muted colours for films without artwork (the comps' colour blocks).
const PALETTE = [
  '#3A3A2C',
  '#2E4A55',
  '#6B2424',
  '#4A3B2E',
  '#2F4F45',
  '#5A2F4E',
  '#24302C',
  '#3C3C3C',
  '#4A4640',
  '#25384A',
  '#5A4A5C',
  '#4E4A2E',
  '#4A2C2C',
  '#3D4A33',
  '#5E4329',
  '#4A2A2A',
  '#3E2E36',
  '#3A3D33',
  '#4E4424',
  '#2B3A47',
];

function hash(text) {
  let h = 0;
  for (let i = 0; i < text.length; i += 1) h = (h * 31 + text.charCodeAt(i)) >>> 0;
  return h;
}

export function placeholderColor(id, title = '') {
  return PALETTE[hash(`${id ?? ''}|${title}`) % PALETTE.length];
}

export function initials(name = '') {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0].toUpperCase())
    .join('');
}
