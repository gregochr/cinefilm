import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { installFetch, renderApp, respond } from '../test/mockApi.jsx';

afterEach(() => vi.unstubAllGlobals());

const SETTINGS = { continueWatchingMinMinutes: 5, inProgressCount: 12 };
const PATH = '/api/admin/settings';

/** Serves the settings and records each PUT; `onPut(body)` may return the response for it. */
function admin({ onPut, get = () => respond(SETTINGS) } = {}) {
  return installFetch((method, url, init) => {
    if (url !== PATH) return null;
    if (method === 'PUT') {
      return onPut ? onPut(JSON.parse(init.body)) : respond({ ...SETTINGS });
    }
    return get();
  });
}

const puts = (fetchMock) =>
  fetchMock.mock.calls.filter((c) => c[0] === PATH && c[1]?.method === 'PUT');
const bodies = (fetchMock) => puts(fetchMock).map((c) => JSON.parse(c[1].body));
const card = () => screen.findByRole('region', { name: 'Continue watching' });
const input = () =>
  screen.findByRole('spinbutton', { name: 'Minimum watched before a film counts as in progress' });

async function open(options) {
  const fetchMock = admin(options);
  renderApp('/admin');
  await card();
  return { fetchMock, field: await input() };
}

describe('Admin: Continue watching card', () => {
  it('loads with the saved value, its suffix, the help text and the count', async () => {
    const { field } = await open();
    const region = await card();
    expect(field).toHaveValue(5);
    expect(field).toHaveAttribute('min', '0');
    expect(field).toHaveAttribute('max', '30');
    expect(field).toHaveAttribute('step', '1');
    expect(within(region).getByText('minutes')).toBeInTheDocument();
    expect(field).toHaveAccessibleDescription(
      'Films you’ve watched for less than this stay out of Continue watching. Their place is still saved.',
    );
    expect(within(region).getByText('12 films currently in progress')).toBeInTheDocument();
    expect(within(region).queryByRole('button')).not.toBeInTheDocument();
  });

  it('says "1 film" for a single film', async () => {
    await open({ get: () => respond({ ...SETTINGS, inProgressCount: 1 }) });
    expect(await screen.findByText('1 film currently in progress')).toBeInTheDocument();
  });

  it('saves on blur, then shows Saved and the count from the response', async () => {
    const { fetchMock, field } = await open({
      onPut: () => respond({ continueWatchingMinMinutes: 10, inProgressCount: 7 }),
    });
    await userEvent.clear(field);
    await userEvent.type(field, '10');
    await userEvent.tab();

    expect(await screen.findByText('7 films currently in progress')).toBeInTheDocument();
    expect(bodies(fetchMock)).toEqual([{ continueWatchingMinMinutes: 10 }]);
    expect(within(await card()).getByRole('status')).toHaveTextContent('Saved');
    expect(field).toHaveValue(10);
  });

  it('saves on Enter, once, even when the field is then left', async () => {
    const { fetchMock, field } = await open({
      onPut: () => respond({ continueWatchingMinMinutes: 0, inProgressCount: 20 }),
    });
    await userEvent.clear(field);
    await userEvent.type(field, '0{Enter}');
    expect(await screen.findByText('20 films currently in progress')).toBeInTheDocument();
    await userEvent.tab();
    expect(bodies(fetchMock)).toEqual([{ continueWatchingMinMinutes: 0 }]);
  });

  it('sends nothing when the value is unchanged', async () => {
    const { fetchMock, field } = await open();
    await userEvent.click(field);
    await userEvent.tab();
    await userEvent.clear(field);
    await userEvent.type(field, '5{Enter}');
    expect(puts(fetchMock)).toHaveLength(0);
    expect(within(await card()).getByRole('status')).toBeEmptyDOMElement();
  });

  it.each(['-1', '31', '2.5', ''])(
    'shows an inline error for "%s" and sends nothing',
    async (typed) => {
      const { fetchMock, field } = await open();
      await userEvent.clear(field);
      if (typed) await userEvent.type(field, typed);
      await userEvent.tab();

      expect(field).toHaveAttribute('aria-invalid', 'true');
      expect(field).toHaveAccessibleDescription(/Enter a whole number of minutes from 0 to 30\./);
      expect(puts(fetchMock)).toHaveLength(0);
      // Typing again clears the error.
      await userEvent.type(field, '1');
      expect(field).not.toHaveAttribute('aria-invalid');
    },
  );

  it('a failed save shows a message and puts the last saved value back', async () => {
    const { fetchMock, field } = await open({ onPut: () => respond({ message: 'no' }, 500) });
    await userEvent.clear(field);
    await userEvent.type(field, '12{Enter}');

    expect(await screen.findByText('Could not save the setting.')).toBeInTheDocument();
    expect(field).toHaveValue(5);
    // The failure is announced in the live status region, not only shown in the page.
    expect(within(await card()).getByRole('status')).toHaveTextContent(
      'Could not save the setting.',
    );
    expect(bodies(fetchMock)).toEqual([{ continueWatchingMinMinutes: 12 }]);
  });

  it('after a failed save the same value can be tried again', async () => {
    let fail = true;
    const { fetchMock, field } = await open({
      onPut: () =>
        fail ? respond({}, 500) : respond({ continueWatchingMinMinutes: 12, inProgressCount: 9 }),
    });
    await userEvent.clear(field);
    await userEvent.type(field, '12{Enter}');
    await screen.findByText('Could not save the setting.');
    fail = false;
    await userEvent.clear(field);
    await userEvent.type(field, '12{Enter}');
    expect(await screen.findByText('9 films currently in progress')).toBeInTheDocument();
    expect(bodies(fetchMock)).toHaveLength(2);
  });

  it('sends the second save only after the first resolved, in order, and ends on the newer value', async () => {
    const gates = [];
    const { fetchMock, field } = await open({
      onPut: (body) =>
        new Promise((resolve) => {
          gates.push({ body, resolve });
        }),
    });
    await userEvent.clear(field);
    await userEvent.type(field, '10{Enter}');
    await userEvent.clear(field);
    await userEvent.type(field, '15{Enter}');
    await vi.waitFor(() => expect(gates).toHaveLength(1));
    expect(bodies(fetchMock)).toEqual([{ continueWatchingMinMinutes: 10 }]);

    gates[0].resolve(respond({ continueWatchingMinMinutes: 10, inProgressCount: 4 }));
    await vi.waitFor(() => expect(gates).toHaveLength(2));
    expect(bodies(fetchMock)).toEqual([
      { continueWatchingMinMinutes: 10 },
      { continueWatchingMinMinutes: 15 },
    ]);
    gates[1].resolve(respond({ continueWatchingMinMinutes: 15, inProgressCount: 3 }));

    expect(await screen.findByText('3 films currently in progress')).toBeInTheDocument();
    expect(field).toHaveValue(15);
  });

  it('shows the later value with no error when an earlier save failed', async () => {
    const gates = [];
    const { field } = await open({
      onPut: (body) =>
        new Promise((resolve) => {
          gates.push({ body, resolve });
        }),
    });
    await userEvent.clear(field);
    await userEvent.type(field, '10{Enter}');
    await userEvent.clear(field);
    await userEvent.type(field, '15{Enter}');
    await vi.waitFor(() => expect(gates).toHaveLength(1));
    gates[0].resolve(respond({}, 500));
    await vi.waitFor(() => expect(gates).toHaveLength(2));
    gates[1].resolve(respond({ continueWatchingMinMinutes: 15, inProgressCount: 3 }));

    expect(await screen.findByText('3 films currently in progress')).toBeInTheDocument();
    expect(field).toHaveValue(15);
    expect(screen.queryByText('Could not save the setting.')).not.toBeInTheDocument();
  });

  it('typing the previous value back while a save is in flight still sends it, after the first', async () => {
    const gates = [];
    const { fetchMock, field } = await open({
      onPut: (body) =>
        new Promise((resolve) => {
          gates.push({ body, resolve });
        }),
    });
    await userEvent.clear(field);
    await userEvent.type(field, '10{Enter}');
    await userEvent.clear(field);
    await userEvent.type(field, '5{Enter}');
    await vi.waitFor(() => expect(gates).toHaveLength(1));
    gates[0].resolve(respond({ continueWatchingMinMinutes: 10, inProgressCount: 4 }));
    await vi.waitFor(() => expect(gates).toHaveLength(2));
    expect(bodies(fetchMock)).toEqual([
      { continueWatchingMinMinutes: 10 },
      { continueWatchingMinMinutes: 5 },
    ]);
    gates[1].resolve(respond({ continueWatchingMinMinutes: 5, inProgressCount: 12 }));
    expect(await screen.findByText('12 films currently in progress')).toBeInTheDocument();
    expect(field).toHaveValue(5);
  });

  it('keeps what is typed while a save is in flight, and the later blur still saves it', async () => {
    const gates = [];
    const { fetchMock, field } = await open({
      onPut: (body) =>
        new Promise((resolve) => {
          gates.push({ body, resolve });
        }),
    });
    await userEvent.clear(field);
    await userEvent.type(field, '10{Enter}');
    await vi.waitFor(() => expect(gates).toHaveLength(1));
    await userEvent.clear(field);
    await userEvent.type(field, '12');
    gates[0].resolve(respond({ continueWatchingMinMinutes: 10, inProgressCount: 4 }));

    expect(await screen.findByText('4 films currently in progress')).toBeInTheDocument();
    expect(field).toHaveValue(12);
    await userEvent.tab();
    await vi.waitFor(() => expect(gates).toHaveLength(2));
    expect(bodies(fetchMock)).toEqual([
      { continueWatchingMinMinutes: 10 },
      { continueWatchingMinMinutes: 12 },
    ]);
  });

  it('keeps what is typed when the save in flight fails', async () => {
    const gates = [];
    const { field } = await open({
      onPut: (body) =>
        new Promise((resolve) => {
          gates.push({ body, resolve });
        }),
    });
    await userEvent.clear(field);
    await userEvent.type(field, '10{Enter}');
    await vi.waitFor(() => expect(gates).toHaveLength(1));
    await userEvent.clear(field);
    await userEvent.type(field, '12');
    gates[0].resolve(respond({}, 500));

    expect(await screen.findByText('Could not save the setting.')).toBeInTheDocument();
    expect(field).toHaveValue(12);
  });

  it('a settings load failure shows the error in the card and leaves the other cards alone', async () => {
    admin({ get: () => respond({}, 500) });
    renderApp('/admin');
    const region = await card();
    expect(await within(region).findByText('Couldn’t load the settings.')).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Library scan' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Session' })).toBeInTheDocument();
    expect(within(region).queryByRole('spinbutton')).not.toBeInTheDocument();
  });

  it('Try again reloads the settings', async () => {
    let fail = true;
    admin({ get: () => (fail ? respond({}, 500) : respond(SETTINGS)) });
    renderApp('/admin');
    const region = await card();
    await within(region).findByText('Couldn’t load the settings.');
    fail = false;
    await userEvent.click(within(region).getByRole('button', { name: 'Try again' }));
    expect(await input()).toHaveValue(5);
  });

  it('shows a loading placeholder until the settings arrive', async () => {
    admin({ get: () => new Promise(() => {}) });
    renderApp('/admin');
    expect(within(await card()).getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });
});
