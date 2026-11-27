// How long the player waits before trying again after a fatal network error, in ms. A leaf module: the engine and the
// bank front both read it, and neither may import the other for it.
export const RECONNECT_MS = 5000;
