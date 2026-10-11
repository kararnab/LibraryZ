// The load itself (see docs/load-testing.md): RATE requests a second for
// DURATION, each one of the things the app does, weighted by how often a
// reader does it. MODE=login measures sign-ins instead.
import http from 'k6/http';
import { check } from 'k6';
import { API, PASSWORD, VOCAB, email, json, auth } from './lib.js';

const USERS = Number(__ENV.USERS || 300);
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';
const MODE = __ENV.MODE || 'mix';

// What a reader's app sends, per 100 requests. Turning a page saves the
// position a second later, so progress saves dominate.
const MIX = [
  ['progress', 50], // PUT /me/library/{id}
  ['work', 10], // GET /works/{id}: a book's page
  ['library', 10], // GET /me/library: My Library
  ['browse', 10], // GET /works: the catalogue
  ['search', 8], // GET /works/search
  ['entry', 5], // GET /me/library/{id}: opening a book
  ['foryou', 5], // GET /me/recommendations
  ['download', 2], // GET /editions/{id}/download (80 KB here)
];
const TOTAL = MIX.reduce((n, [, w]) => n + w, 0);
const NAMES = MIX.map(([n]) => n).concat(['login']);

export const options = {
  setupTimeout: '10m',
  discardResponseBodies: true,
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE),
      // Capped: past saturation, overload shows as dropped iterations, not a
      // k6 that runs out of memory spawning VUs.
      maxVUs: Math.max(200, RATE),
    },
  },
  // One threshold per request kind, so the summary reports each one's p95.
  // Plus the load phase as a whole, without setup's sign-ins.
  thresholds: Object.fromEntries(NAMES.map((n) => [`http_req_duration{name:${n}}`, ['p(95)>=0']]).concat([
    ['http_req_duration{scenario:load}', ['p(95)>=0']],
    ['http_req_failed{scenario:load}', ['rate>=0']],
    ['http_reqs{scenario:load}', ['count>=0']],
  ])),
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const logins = [];
  for (let i = 0; i < USERS; i += 16) {
    const batch = [];
    for (let j = i; j < Math.min(USERS, i + 16); j++) {
      batch.push(['POST', `${API[j % API.length]}/auth/login`, JSON.stringify({ email: email(j), password: PASSWORD }),
        { ...json, responseType: 'text' }]);
    }
    logins.push(...http.batch(batch));
  }
  const tokens = logins.map((r, i) => {
    if (r.status !== 200) throw new Error(`login ${i}: ${r.status} ${r.body}`);
    return r.json('access_token');
  });

  const works = [];
  const editions = [];
  for (let offset = 0; ; offset += 200) {
    const page = http.get(`${API[0]}/works?limit=200&offset=${offset}`, { responseType: 'text' }).json();
    for (const w of page) {
      works.push(w.id);
      for (const e of w.editions || []) editions.push(e.id);
    }
    if (page.length < 200) break;
  }
  return { tokens, works, editions };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export default function (data) {
  const base = pick(API);
  if (MODE === 'login') {
    const i = Math.floor(Math.random() * USERS);
    const r = http.post(`${base}/auth/login`, JSON.stringify({ email: email(i), password: PASSWORD }),
      { ...json, tags: { name: 'login' } });
    check(r, { ok: (x) => x.status === 200 });
    return;
  }

  const u = Math.floor(Math.random() * data.tokens.length);
  const h = auth(data.tokens[u]).headers;
  let roll = Math.random() * TOTAL;
  const [kind] = MIX.find(([, w]) => (roll -= w) < 0);
  const work = pick(data.works);
  const params = { headers: h, tags: { name: kind } };
  let r;
  switch (kind) {
    case 'progress':
      r = http.put(`${base}/me/library/${work}`,
        JSON.stringify({ status: 'reading', progress_percent: Math.floor(Math.random() * 100) }), params);
      break;
    case 'work': r = http.get(`${base}/works/${work}`, params); break;
    case 'library': r = http.get(`${base}/me/library`, params); break;
    case 'browse': r = http.get(`${base}/works?limit=50&offset=${50 * Math.floor(Math.random() * 10)}`, params); break;
    case 'search': r = http.get(`${base}/works/search?q=${pick(VOCAB)}&limit=50`, params); break;
    case 'entry':
      // A book that isn't on this reader's shelf answers 404; that's an answer.
      r = http.get(`${base}/me/library/${work}`, { ...params, responseCallback: http.expectedStatuses({ min: 200, max: 399 }, 404) });
      break;
    case 'foryou': r = http.get(`${base}/me/recommendations`, params); break;
    case 'download': r = http.get(`${base}/editions/${pick(data.editions)}/download`, params); break;
  }
  check(r, { ok: (x) => x.status < 400 || (kind === 'entry' && x.status === 404) });
}

export function handleSummary(data) {
  return { [`${__ENV.OUT || '/out'}/summary.json`]: JSON.stringify(data, null, 1) };
}
