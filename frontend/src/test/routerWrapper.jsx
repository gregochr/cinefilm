import { MemoryRouter } from 'react-router-dom';

/** A renderHook `wrapper` that provides a MemoryRouter at `path`. */
export function routerWrapper(path = '/') {
  return function RouterWrapper({ children }) {
    return <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>;
  };
}
