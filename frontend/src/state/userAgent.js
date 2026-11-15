// A human name for a user agent, for the Admin "Playing now" card. Pure; no DOM. The client words its own user
// agent, so every field may be missing: the fallback is "Unknown device".
import { statusStrip } from './adminCopy.js';

/** The browser, from the tokens that tell Chrome-family browsers apart (Edge and Opera also say "Chrome"). */
function browserOf(ua) {
  if (/Edg(e|A|iOS)?\//.test(ua)) return 'Edge';
  if (/OPR\//.test(ua)) return 'Opera';
  if (/Firefox\/|FxiOS\//.test(ua)) return 'Firefox';
  if (/Chrome\/|CriOS\//.test(ua)) return 'Chrome';
  if (/Safari\//.test(ua) && /Version\//.test(ua)) return 'Safari';
  return null;
}

/** The operating system of a desktop. */
function systemOf(ua) {
  if (/Windows/.test(ua)) return 'Windows';
  if (/Macintosh|Mac OS X/.test(ua)) return 'Mac';
  if (/CrOS/.test(ua)) return 'ChromeOS';
  if (/Linux|X11/.test(ua)) return 'Linux';
  return null;
}

/**
 * "Safari on Mac", "Chrome on Windows"; an iPhone, an iPad and an Android device are named on their own, without a
 * browser. The platform alone when the browser is unknown, the browser alone when the platform is, else
 * "Unknown device". The one describer for the whole app (the Playback log uses it too).
 *
 * iPadOS Safari sends a desktop Macintosh user agent by default, which the user agent alone cannot tell from a Mac.
 * The player reports whether the device has more than one touch point (`touch`): a Macintosh user agent with
 * `touch === true` is an iPad ("Safari on iPad"). A missing or false
 * `touch` (an older report) leaves the Mac reading as it was. Touch changes nothing else: an iPhone is an iPhone.
 */
export function describeDevice(userAgent, touch = false) {
  const ua = typeof userAgent === 'string' ? userAgent : '';
  if (/iPhone/.test(ua)) return 'iPhone';
  if (/iPad/.test(ua)) return 'iPad';
  if (/Android/.test(ua)) return 'Android';
  const system = touch === true && /Macintosh/.test(ua) ? 'iPad' : systemOf(ua);
  const browser = browserOf(ua);
  if (browser && system) return `${browser} on ${system}`;
  return system ?? browser ?? statusStrip.unknownDevice;
}
