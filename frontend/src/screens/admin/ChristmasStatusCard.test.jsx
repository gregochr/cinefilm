import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { christmasFixture } from '../../test/christmasFixture.js';
import ChristmasStatusCard from './ChristmasStatusCard.jsx';

const card = () => screen.getByRole('region', { name: 'Christmas week status' });

describe('ChristmasStatusCard', () => {
  it('says how many films show, the week, and the Home rule', () => {
    render(<ChristmasStatusCard data={christmasFixture()} />);
    expect(card()).toHaveTextContent(
      '14 films will show in Christmas week (Mon 14 Dec to Sun 20 Dec).',
    );
    expect(card()).toHaveTextContent(
      'Home shows up to 12, oldest first. Under 5 and it falls back to the usual weekly theme.',
    );
  });

  it('draws a decorative meter: 12 filled slots and one hollow segment per film over', () => {
    render(<ChristmasStatusCard data={christmasFixture()} />);
    const meter = screen.getByTestId('meter');
    expect(meter).toHaveAttribute('aria-hidden', 'true');
    expect(meter).toHaveAttribute('data-filled', '12');
    expect(meter).toHaveAttribute('data-over', '2');
    expect(card()).toHaveTextContent('12 places on Home');
    expect(card()).toHaveTextContent('2 over');
  });

  it('shows no "over" caption at exactly 12 films', () => {
    const data = christmasFixture();
    data.inWeek = data.inWeek.slice(0, 12);
    render(<ChristmasStatusCard data={data} />);
    expect(screen.getByTestId('meter')).toHaveAttribute('data-over', '0');
    expect(card()).not.toHaveTextContent('over');
  });

  it('shows the keyword strip and its progress while keywords are being fetched', () => {
    render(<ChristmasStatusCard data={christmasFixture()} />);
    expect(card()).toHaveTextContent(
      'Keywords fetched for 1,374 of 1,412 films. The other 38 are on their way, so this list may still grow.',
    );
    expect(screen.getByTestId('keyword-fill')).toHaveStyle({ width: '97%' });
  });

  it('announces keyword progress politely, as a status message', () => {
    render(<ChristmasStatusCard data={christmasFixture()} />);
    expect(screen.getByRole('status')).toHaveTextContent('Keywords fetched for 1,374 of 1,412');
  });

  it('hides the keyword strip once every film has its keywords', () => {
    const data = christmasFixture();
    data.keywords = { fetched: 1412, total: 1412, running: false };
    render(<ChristmasStatusCard data={data} />);
    expect(screen.queryByTestId('keyword-fill')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(card()).not.toHaveTextContent('Keywords fetched');
  });

  it('under 5 says Christmas week falls back and how many more are needed', () => {
    const data = christmasFixture();
    data.inWeek = data.inWeek.slice(0, 3);
    data.fallsBack = true;
    render(<ChristmasStatusCard data={data} />);
    expect(card()).toHaveTextContent(
      '3 films are in, which is under 5, so Christmas week shows the usual weekly theme instead.',
    );
    expect(card()).toHaveTextContent('Home needs at least 5 Christmas films to show them.');
  });
});
