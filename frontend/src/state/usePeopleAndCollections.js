import { collectionsApi, peopleApi } from '../api/resources.js';
import { useResource } from './useResource.js';

export function usePerson(id) {
  return useResource(`person:${id}`, (options) => peopleApi.get(id, options));
}

/** The Collections tab: `{ filmCount, collections }`. */
export function useCollections() {
  return useResource('collections', (options) => collectionsApi.list(options));
}

export function useCollection(id) {
  return useResource(`collection:${id}`, (options) => collectionsApi.get(id, options));
}
