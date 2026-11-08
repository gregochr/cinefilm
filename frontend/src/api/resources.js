import { get, put, post, del } from './client.js';

export const homeApi = {
  get: (options) => get('/api/home', options),
};

export function buildFilmsQuery(params = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : '';
}

export const filmsApi = {
  list: (params, options) => get(`/api/films${buildFilmsQuery(params)}`, options),
  get: (id, options) => get(`/api/films/${id}`, options),
  savePreferences: (id, preferences) => put(`/api/films/${id}/preferences`, preferences),
};

export const peopleApi = {
  get: (id, options) => get(`/api/people/${id}`, options),
};

export const collectionsApi = {
  get: (id, options) => get(`/api/collections/${id}`, options),
};

export const editionsApi = {
  streamUrl: (id) => `/api/editions/${id}/stream`,
  reportProgress: (id, positionMs) => put(`/api/editions/${id}/progress`, { positionMs }),
  setWatched: (id, watched) => post(`/api/editions/${id}/watched`, { watched }),
};

export const sessionApi = {
  status: () => get('/api/session'),
  signIn: (password) => post('/api/session', { password }),
  signOut: () => del('/api/session'),
};
