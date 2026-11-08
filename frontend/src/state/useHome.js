import { homeApi } from '../api/resources.js';
import { useResource } from './useResource.js';

export function useHome() {
  return useResource('home', (options) => homeApi.get(options));
}
