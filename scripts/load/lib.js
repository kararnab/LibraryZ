// Shared by the load-test scripts (see docs/load-testing.md).

// Both API instances, reached directly on the compose network (past Kong).
export const API = (__ENV.API || 'http://libraryz-1:8080,http://libraryz-2:8080').split(',');
export const PASSWORD = 'load-test-password';
export const VOCAB = [
  'Silent', 'River', 'Garden', 'Winter', 'Letters', 'Island', 'Night', 'Glass', 'Orchard', 'Storm',
  'Harbour', 'Lantern', 'Mountain', 'Shadow', 'Summer', 'Mirror', 'Forest', 'Paper', 'Stone', 'Crown',
];

export const email = (i) => `load-${i}@libraryz.local`;
export const json = { headers: { 'Content-Type': 'application/json' } };
export const auth = (token) => ({ headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });
