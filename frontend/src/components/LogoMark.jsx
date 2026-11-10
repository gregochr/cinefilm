/**
 * The Gate: a rounded cine-film frame left open on the right so it reads as a C, with an
 * amber lamp inside. Decorative (aria-hidden) unless given a title. Colour comes from
 * `currentColor`, which `.logo-mark` sets to --cf-amber.
 */
export default function LogoMark({ size = 30, title }) {
  const a11y = title ? { role: 'img' } : { 'aria-hidden': 'true' };
  return (
    <svg
      className="logo-mark"
      data-testid="logo-mark"
      width={size}
      height={size}
      viewBox="0 0 64 64"
      focusable="false"
      {...a11y}
    >
      {title ? <title>{title}</title> : null}
      <path
        d="M50 14 H20 A10 10 0 0 0 10 24 V40 A10 10 0 0 0 20 50 H50"
        fill="none"
        stroke="currentColor"
        strokeWidth="6.5"
        strokeLinecap="round"
      />
      <circle cx="38" cy="32" r="6.5" fill="currentColor" />
    </svg>
  );
}
