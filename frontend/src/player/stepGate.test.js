import { describe, expect, it } from 'vitest';
import { HEALTHY_MS, createStepGate } from './stepGate.js';

// A clock the test moves by hand.
function gateAt(startLevel) {
  let t = 1_000_000;
  const gate = createStepGate({ now: () => t });
  if (startLevel !== undefined) gate.start(startLevel);
  return { gate, advance: (ms) => (t += ms) };
}

describe('Auto step-up gating (decision 3)', () => {
  it('waits about 60 s before stepping up: 59,999 ms no, 60,000 ms yes', () => {
    const { gate, advance } = gateAt(1);
    advance(HEALTHY_MS - 1);
    expect(gate.tick(3)).toBe(1);
    advance(1);
    expect(gate.tick(3)).toBe(2);
  });

  it('steps up one level at a time, each after a further healthy minute', () => {
    const { gate, advance } = gateAt(0);
    advance(HEALTHY_MS);
    expect(gate.tick(3)).toBe(1);
    advance(HEALTHY_MS - 1);
    expect(gate.tick(3)).toBe(1);
    advance(1);
    expect(gate.tick(3)).toBe(2);
    advance(HEALTHY_MS);
    expect(gate.tick(3)).toBe(3);
  });

  it('never goes above the top level', () => {
    const { gate, advance } = gateAt(3);
    advance(HEALTHY_MS * 5);
    expect(gate.tick(3)).toBe(3);
  });

  it('steps down at once and restarts the clock', () => {
    const { gate, advance } = gateAt(3);
    advance(HEALTHY_MS - 1);
    expect(gate.switched(1)).toBe(1);
    advance(HEALTHY_MS - 1);
    expect(gate.tick(3)).toBe(1);
    advance(1);
    expect(gate.tick(3)).toBe(2);
  });

  it('ignores a switch that is not a step down', () => {
    const { gate, advance } = gateAt(2);
    advance(30000);
    expect(gate.switched(2)).toBe(2);
    advance(30000);
    expect(gate.tick(3)).toBe(3); // the 60 s clock was not restarted
  });

  it('a stall restarts the healthy clock', () => {
    const { gate, advance } = gateAt(1);
    advance(HEALTHY_MS - 1);
    gate.trouble();
    advance(HEALTHY_MS - 1);
    expect(gate.tick(3)).toBe(1);
    advance(1);
    expect(gate.tick(3)).toBe(2);
  });

  it('has no cap until a level is started, and the first switch sets it', () => {
    const { gate, advance } = gateAt();
    advance(HEALTHY_MS * 2);
    expect(gate.tick(3)).toBeNull();
    expect(gate.switched(2)).toBe(2);
  });

  it('uses the real clock when none is given', () => {
    const gate = createStepGate();
    gate.start(0);
    expect(gate.tick(3)).toBe(0);
  });
});

describe('arrived()', () => {
  it('restarts the healthy clock and leaves the cap as it was', () => {
    const { gate, advance } = gateAt(1);
    advance(HEALTHY_MS * 5);
    gate.arrived();
    advance(HEALTHY_MS - 1);
    expect(gate.tick(3)).toBe(1);
    advance(1);
    expect(gate.tick(3)).toBe(2);
  });

  it('does nothing harmful before start', () => {
    const { gate, advance } = gateAt();
    gate.arrived();
    advance(HEALTHY_MS);
    expect(gate.tick(3)).toBe(null);
    expect(gate.start(1)).toBe(1);
  });
});
