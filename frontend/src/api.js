// Small fetch wrapper. Errors from the backend carry {code, message}.
const BASE = import.meta.env.VITE_API_BASE || '';

async function request(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const err = new Error(data?.message || res.statusText);
    err.code = data?.code;
    err.status = res.status;
    throw err;
  }
  return data;
}

export const api = {
  overview: () => request('GET', '/api/overview'),
  experiments: () => request('GET', '/api/experiments'),
  experiment: (id) => request('GET', `/api/experiments/${id}`),
  createExperiment: (body) => request('POST', '/api/experiments', body),
  transition: (id, body) => request('POST', `/api/experiments/${id}/transition`, body),
  analyze: (id, triggeredBy) => request('POST', `/api/experiments/${id}/analyze`, { triggeredBy }),
  decide: (id, body) => request('POST', `/api/experiments/${id}/decisions`, body),
  run: (runId) => request('GET', `/api/runs/${runId}`),
  metrics: () => request('GET', '/api/metrics'),
  sampleSize: (q) => request('GET', '/api/design/sample-size?' + new URLSearchParams(q)),
  generate: (body) => request('POST', '/api/generator/run', body),
  generationRuns: () => request('GET', '/api/generator/runs'),
  validate: () => request('POST', '/api/validation/run'),
  validation: () => request('GET', '/api/validation/latest'),
  issues: (q) => request('GET', '/api/validation/issues?' + new URLSearchParams(q)),
  groundTruth: () => request('GET', '/api/ground-truth/summary'),
  exportData: () => request('POST', '/api/export'),
  manifest: () => request('GET', '/api/export/manifest'),
  repro: (body) => request('POST', '/api/reproducibility/check', body),
  dictionary: () => request('GET', '/api/dictionary'),
  data: (table, q) => request('GET', `/api/data/${table}?` + new URLSearchParams(q)),
};
