import { adminApi } from '../api/resources.js';
import { useResource } from './useResource.js';

/**
 * The stored journeys, newest run first. `journeys` is null until the first answer; `reload` reads again with the
 * rows already read left in place. A sign-in after a 401 reads again by itself, as `useResource` does.
 */
export function useJourneys() {
  const { data, loading, error, reload } = useResource('admin-journeys', (options) =>
    adminApi.journeys(options),
  );
  return { journeys: data?.journeys ?? null, loading, error, reload };
}
