import { Link } from 'react-router-dom';

const cls = (variant, size, extra) =>
  ['btn', `btn--${variant}`, size ? `btn--${size}` : '', extra || ''].filter(Boolean).join(' ');

/** variant: primary (amber) | secondary | icon. */
export function Button({ variant = 'secondary', size, className, type = 'button', ...rest }) {
  return <button type={type} className={cls(variant, size, className)} {...rest} />;
}

export function ButtonLink({ variant = 'secondary', size, className, ...rest }) {
  return <Link className={cls(variant, size, className)} {...rest} />;
}
