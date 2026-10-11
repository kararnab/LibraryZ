// Seeds a load-test instance (see docs/load-testing.md): USERS accounts,
// WORKS books (EDITIONS of them with a small TXT file), and PER_USER shelf
// entries per account, some rated, so recommendations have something to
// train on. Run once, with one VU, against a fresh stack.
import http from 'k6/http';
import { check, fail } from 'k6';
import { API, PASSWORD, VOCAB, email, json, auth } from './lib.js';

const USERS = Number(__ENV.USERS || 300);
const WORKS = Number(__ENV.WORKS || 500);
const EDITIONS = Number(__ENV.EDITIONS || 50);
const PER_USER = Number(__ENV.PER_USER || 20);

export const options = { vus: 1, iterations: 1, setupTimeout: '30m', teardownTimeout: '1m' };

// A deterministic stand-in for Math.random, so every run seeds the same data.
let state = 42;
function rand(n) {
  state = (state * 1103515245 + 12345) & 0x7fffffff;
  return state % n;
}

function batched(requests, size = 16) {
  const out = [];
  for (let i = 0; i < requests.length; i += size) out.push(...http.batch(requests.slice(i, i + size)));
  return out;
}

export default function () {
  // Accounts (sign-up returns a session, so no separate login).
  const signups = batched(
    Array.from({ length: USERS }, (_, i) => ['POST', `${API[i % API.length]}/auth/signup`,
      JSON.stringify({ email: email(i), password: PASSWORD, name: `Load ${i}` }), json]),
  );
  const tokens = signups.map((r, i) => {
    if (!check(r, { 'signup 201': (x) => x.status === 201 })) fail(`signup ${i}: ${r.status} ${r.body}`);
    return r.json('access_token');
  });

  // Books, titled from a small vocabulary so searches find something.
  const created = batched(
    Array.from({ length: WORKS }, (_, i) => {
      const title = `The ${VOCAB[rand(VOCAB.length)]} ${VOCAB[rand(VOCAB.length)]} ${i}`;
      const body = { title, authors: `Author ${rand(80)}`, language: 'en', publication_year: 1800 + rand(220) };
      return ['POST', `${API[i % API.length]}/works`, JSON.stringify(body), auth(tokens[i % USERS])];
    }),
  );
  const works = created.map((r, i) => {
    if (!check(r, { 'work 201': (x) => x.status === 201 })) fail(`work ${i}: ${r.status} ${r.body}`);
    return r.json('id');
  });

  // A small text edition on the first EDITIONS books, each uploaded by a
  // different account (new accounts have a small upload allowance).
  const text = 'It was a quiet afternoon in the library. '.repeat(2000); // ~80 KB
  for (let i = 0; i < EDITIONS; i++) {
    const r = http.post(`${API[i % API.length]}/works/${works[i]}/editions`, {
      format: 'txt',
      language: 'en',
      file: http.file(`${text}\n${i}`, `book-${i}.txt`, 'text/plain'),
    }, { headers: { Authorization: `Bearer ${tokens[i]}` } });
    if (!check(r, { 'edition 201': (x) => x.status === 201 })) fail(`edition ${i}: ${r.status} ${r.body}`);
  }

  // Shelves: popular books more often, as in a real catalogue.
  const shelf = [];
  for (let u = 0; u < USERS; u++) {
    const picked = new Set();
    while (picked.size < PER_USER) picked.add(Math.min(rand(WORKS), rand(WORKS)));
    for (const w of picked) {
      const roll = rand(10);
      const body = roll < 4
        ? { status: 'read', rating: 1 + rand(5) }
        : roll < 7 ? { status: 'reading', progress_percent: rand(100) } : { status: 'want' };
      shelf.push(['PUT', `${API[u % API.length]}/me/library/${works[w]}`, JSON.stringify(body), auth(tokens[u])]);
    }
  }
  batched(shelf).forEach((r, i) => {
    if (!check(r, { 'shelf 200': (x) => x.status === 200 })) fail(`shelf ${i}: ${r.status} ${r.body}`);
  });
  console.log(`seeded ${USERS} users, ${WORKS} works, ${EDITIONS} editions, ${shelf.length} shelf entries`);
}
