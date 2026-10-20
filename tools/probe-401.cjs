/**
 * What does "请升级最新版APP" actually mean?
 *
 * The phone gets `-1 请升级最新版APP` from dev/start and dev/end while the
 * emulator gets business codes from the same endpoints. Before shipping another
 * build that guesses, ask the server directly: same URL, different header sets,
 * and see which of them produces that sentence.
 *
 * Nothing here can open a valve: no valid session exists on this machine right
 * now, which is the whole point of the exercise.
 */
const BASE = 'https://i.ilife798.com/api/v1/';
const DID = '865137068739782';
const TOKEN = process.env.PROBE_TOKEN || '';
const PATH = `${BASE}dev/start?did=${DID}&upgrade=true&ptype=91&args=&rcp=false&cnt=1`;

const combos = [
  { name: 'no headers at all', headers: {} },
  { name: 'ApplicationType 1,1 only', headers: { ApplicationType: '1,1' } },
  { name: 'ApplicationType 1,5 only', headers: { ApplicationType: '1,5' } },
  { name: 'dead token + 1,1 + vc 31009', headers: { Authorization: TOKEN, ApplicationType: '1,1', VersionCode: '31009' } },
  { name: 'dead token + 1,5 + vc 31009', headers: { Authorization: TOKEN, ApplicationType: '1,5', VersionCode: '31009' } },
  { name: 'dead token + 1,5 + vc 45000', headers: { Authorization: TOKEN, ApplicationType: '1,5', VersionCode: '45000' } },
  { name: 'garbage token + 1,5 + vc 31009', headers: { Authorization: 'x'.repeat(32), ApplicationType: '1,5', VersionCode: '31009' } },
  { name: 'empty token + 1,5 + vc 31009', headers: { Authorization: '', ApplicationType: '1,5', VersionCode: '31009' } },
];

(async () => {
  console.log('token present:', TOKEN ? 'yes (' + TOKEN.length + ' chars)' : 'NO -- server will see an anonymous call');
  for (const combo of combos) {
    try {
      const res = await fetch(PATH, {
        method: 'GET',
        headers: { Accept: 'application/json', ...combo.headers },
      });
      const text = await res.text();
      const body = text.length > 120 ? text.slice(0, 120) + '…' : text;
      console.log(`  ${String(res.status).padEnd(4)} ${combo.name.padEnd(32)} ${body}`);
    } catch (e) {
      console.log(`  ERR  ${combo.name.padEnd(32)} ${e.message}`);
    }
  }
})();