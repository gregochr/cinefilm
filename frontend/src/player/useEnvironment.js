import { useCallback, useEffect, useRef, useState } from 'react';
import { rememberAnswer, recallAnswer } from '../state/networks.js';
import { classify, isRelay, needsLookup } from './environment.js';
import { measureLatency } from './latency.js';
import { lookupOperator } from './operator.js';

const INITIAL = { environment: 'unknown', operator: null, latencyMs: null, question: null };

/**
 * Where the film is being watched, decided once when the player opens: the network round trip, then (away from
 * home) the operator's name and what this browser remembers about it. Returns `environment`, `operator`,
 * `latencyMs`, `question` (`{ operator }` while the card is due, where `operator` is the name an answer will be
 * stored under, or null when there is none to store it under), `answer(environment)` and `dismiss()`.
 *
 * It sets no state until it learns something, and nothing here can break playback. `measure` and `lookup` are
 * effect dependencies: pass stable functions.
 */
export function useEnvironment({ measure = measureLatency, lookup = lookupOperator } = {}) {
  const [state, setState] = useState(INITIAL);
  // The open question, kept in a ref too so a second click or a stray call finds it already closed.
  const questionRef = useRef(null);

  useEffect(() => {
    const controller = new AbortController();
    let closed = false;
    // Detection runs on its own: nothing waits for it, and it swallows its own failures.
    void (async () => {
      try {
        const latencyMs = await measure({ signal: controller.signal });
        if (closed || !Number.isFinite(latencyMs)) return;
        if (!needsLookup(latencyMs)) {
          setState((s) => ({ ...s, environment: 'home', latencyMs }));
          return;
        }
        // The record should have the latency even if the player is left during the lookup.
        setState((s) => ({ ...s, latencyMs }));
        const operator = await lookup({ signal: controller.signal });
        if (closed) return;
        const storable = typeof operator === 'string' && !isRelay(operator) ? operator : null;
        const { environment, ask } = classify({ latencyMs, answer: recallAnswer(storable) });
        const question = ask ? { operator: storable } : null;
        questionRef.current = question;
        setState((s) => ({ ...s, environment, operator: operator ?? null, latencyMs, question }));
      } catch {
        // Detection failed: the state stays as it is.
      }
    })();
    return () => {
      closed = true;
      controller.abort();
    };
  }, [measure, lookup]);

  const answer = useCallback((environment) => {
    const question = questionRef.current;
    if (question === null) return false;
    questionRef.current = null;
    const stored = rememberAnswer(question.operator, environment);
    setState((s) => ({ ...s, environment, question: null }));
    return stored;
  }, []);

  const dismiss = useCallback(() => {
    questionRef.current = null;
    setState((s) => ({ ...s, question: null }));
  }, []);

  return { ...state, answer, dismiss };
}
