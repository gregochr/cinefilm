import { collectionsApi, peopleApi } from '../api/resources.js';
import { useResource } from './useResource.js';

const REVALIDATE = { cache: 'revalidate' };

export function usePerson(id) {
  return useResource(`person:${id}`, (options) => peopleApi.get(id, options), REVALIDATE);
}

/** The Collections tab: `{ filmCount, collections }`. */
export function useCollections() {
  return useResource('collections', (options) => collectionsApi.list(options), REVALIDATE);
}

export function useCollection(id) {
  return useResource(`collection:${id}`, (options) => collectionsApi.get(id, options), REVALIDATE);
}
