import { homeApi } from '../api/resources.js';
import { useResource } from './useResource.js';

const REVALIDATE = { cache: 'revalidate' };

export function useHome() {
  return useResource('home', (options) => homeApi.get(options), REVALIDATE);
}
