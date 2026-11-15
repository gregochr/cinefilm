import { useRef } from 'react';
import { buttons, networks as copy, rememberedNetworksNote } from '../state/adminCopy.js';
import { environmentLabel, operatorName } from '../state/healthFormat.js';
import { useRememberedNetworks } from '../state/useRememberedNetworks.js';
import { Button } from './Button.jsx';

/** Admin Playback: the answers to "Where are you watching?" this browser holds, one row per network, each with Forget. */
export default function RememberedNetworksPanel() {
  const { networks, failed, forget } = useRememberedNetworks();
  const heading = useRef(null);
  // The Forget button that had focus leaves with its row: focus goes to the card's own heading.
  const onForget = (operator) => {
    if (forget(operator)) heading.current.focus();
  };
  return (
    <section className="admin__card" aria-labelledby="admin-networks-title">
      <div className="admin__head">
        <h2 id="admin-networks-title" className="admin__title" ref={heading} tabIndex={-1}>
          {copy.title}
        </h2>
        <span className="admin__text">{copy.why}</span>
      </div>
      {networks.length === 0 ? (
        <p className="admin__text">{copy.empty}</p>
      ) : (
        <>
          <ul className="admin__list" aria-label={copy.title}>
            {networks.map(({ operator, environment }) => {
              const name = operatorName(operator) ?? operator;
              return (
                <li key={operator} className="admin__item plog-network">
                  <div>
                    <div className="admin__item-title">
                      {environmentLabel(environment)} · {name}
                    </div>
                    {failed === operator ? (
                      <p className="plog-error" role="alert">
                        {copy.forgetFailed}
                      </p>
                    ) : null}
                  </div>
                  <Button
                    aria-label={`${buttons.forget.label} ${name}`}
                    onClick={() => onForget(operator)}
                  >
                    {buttons.forget.label}
                  </Button>
                </li>
              );
            })}
          </ul>
          <p className="admin__notice">{rememberedNetworksNote}</p>
        </>
      )}
    </section>
  );
}
