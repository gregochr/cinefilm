const base = {
  width: 18,
  height: 18,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
  focusable: false,
};

const make = (children, overrides = {}) =>
  function Icon({ size, ...rest }) {
    const props = { ...base, ...overrides, ...rest };
    if (size) {
      props.width = size;
      props.height = size;
    }
    return <svg {...props}>{children}</svg>;
  };

export const SearchIcon = make(
  <>
    <circle cx="11" cy="11" r="7" />
    <path d="m20 20-3.5-3.5" />
  </>,
);
export const SettingsIcon = make(
  <>
    <path d="M4 7h9M17 7h3M4 12h3M11 12h9M4 17h11M19 17h1" />
    <circle cx="15" cy="7" r="2" />
    <circle cx="9" cy="12" r="2" />
    <circle cx="17" cy="17" r="2" />
  </>,
  { strokeWidth: 1.6 },
);
export const PlayIcon = make(<path d="M7 4.5v15l13-7.5z" />, {
  fill: 'currentColor',
  stroke: 'none',
});
export const PauseIcon = make(
  <>
    <rect x="6" y="4.5" width="4" height="15" rx="1" />
    <rect x="14" y="4.5" width="4" height="15" rx="1" />
  </>,
  { fill: 'currentColor', stroke: 'none' },
);
export const CheckIcon = make(<path d="m5 12 5 5 9-10" />, { strokeWidth: 2.2 });
export const RestartIcon = make(
  <>
    <path d="M4 12a8 8 0 1 0 2.5-5.8" />
    <path d="M4 4v4h4" />
  </>,
);
export const BackIcon = make(<path d="m15 5-7 7 7 7" />, { strokeWidth: 2 });
export const HomeIcon = make(<path d="M4 11 12 4l8 7v9h-5v-6H9v6H4z" />);
export const LibraryIcon = make(
  <>
    <rect x="4" y="4" width="6" height="16" rx="1" />
    <rect x="14" y="4" width="6" height="16" rx="1" />
  </>,
);
export const SkipBackIcon = make(
  <>
    <path d="M4 12a8 8 0 1 0 2.5-5.8" />
    <path d="M4 4v4h4" />
    <text x="12" y="15.5" fontSize="7.5" textAnchor="middle" fill="currentColor" stroke="none">
      10
    </text>
  </>,
);
export const SkipForwardIcon = make(
  <>
    <path d="M20 12a8 8 0 1 1-2.5-5.8" />
    <path d="M20 4v4h-4" />
    <text x="12" y="15.5" fontSize="7.5" textAnchor="middle" fill="currentColor" stroke="none">
      10
    </text>
  </>,
);
export const VolumeIcon = make(
  <>
    <path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" />
    <path d="M15.5 9a4 4 0 0 1 0 6" />
  </>,
);
export const MutedIcon = make(
  <>
    <path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" />
    <path d="m16 9.5 4 5m0-5-4 5" />
  </>,
);
export const CaptionsIcon = make(
  <>
    <rect x="3" y="5" width="18" height="14" rx="2" />
    <path d="M7 11h4M13 11h4M7 15h2M12 15h5" />
  </>,
);
export const QualityIcon = make(
  <>
    <path d="M4 7h9M17 7h3M4 12h3M11 12h9M4 17h11M19 17h1" />
    <circle cx="15" cy="7" r="2" />
    <circle cx="9" cy="12" r="2" />
    <circle cx="17" cy="17" r="2" />
  </>,
  { strokeWidth: 1.6 },
);
export const FullscreenIcon = make(<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />, {
  strokeWidth: 2,
});
