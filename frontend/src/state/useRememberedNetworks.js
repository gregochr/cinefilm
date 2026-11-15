import { useCallback, useState } from 'react';
import { forgetNetwork, rememberedNetworks } from './networks.js';

/**
 * The networks this browser remembers, newest answer first, read once when the card opens (no polling, no storage
 * events). `forget(operator)` removes one and returns whether it is gone; when the browser refuses the write the
 * network stays and `failed` names it, so the card can say so beside that row.
 */
export function useRememberedNetworks() {
  const [networks, setNetworks] = useState(() => rememberedNetworks().toReversed());
  const [failed, setFailed] = useState(null);
  const forget = useCallback((operator) => {
    const left = forgetNetwork(operator).toReversed();
    const gone = !left.some((entry) => entry.operator === operator);
    setNetworks(left);
    setFailed(gone ? null : operator);
    return gone;
  }, []);
  return { networks, failed, forget };
}
