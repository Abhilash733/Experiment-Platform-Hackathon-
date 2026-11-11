import { useCallback, useEffect, useState } from 'react';

// ---------- formatting
export const fmtInt = (v) => (v == null ? '—' : Number(v).toLocaleString());
export const fmtPct = (v, d = 1) => (v == null ? '—' : `${(v * 100).toFixed(d)}%`);
export const fmtSignedPct = (v, d = 1) => (v == null ? '—' : `${v >= 0 ? '+' : ''}${(v * 100).toFixed(d)}%`);
export const fmtNum = (v, d = 2) => (v == null ? '—' : Number(v).toFixed(d));
export const fmtP = (p) => (p == null ? '—' : p < 0.0001 ? '<0.0001' : Number(p).toFixed(4));
export const fmtDate = (v) => (v ? String(v).replace('T', ' ').slice(0, 16) : '—');
export const fmtMetric = (v, unit) => (v == null ? '—' : unit === 'ms' ? `${Number(v).toFixed(0)} ms` : fmtPct(v, 2));

// ---------- status tones (status colors always ship with a text label)
const TONES = {
  SHIP: 'good', PASS: 'good', DECIDED: 'good', COMPLETE: 'good', true: 'good',
  INCONCLUSIVE: 'neutral', ITERATE: 'neutral', DRAFT: 'neutral', ARCHIVED: 'neutral', CANCELLED: 'neutral',
  WARN: 'warning', DESIGNED: 'info', RUNNING: 'info', STOPPED: 'info', ANALYZED: 'info',
  BLOCKED_GUARDRAIL: 'serious', DO_NOT_SHIP: 'serious', ROLLBACK: 'serious',
  INVALID_SRM: 'critical', FAIL: 'critical', false: 'critical',
};
export function Badge({ value, tone, children }) {
  const t = tone || TONES[value] || 'neutral';
  return (
    <span className={`badge ${t}`}>
      <span className="dot" aria-hidden="true" />
      {children ?? String(value ?? '—').replaceAll('_', ' ')}
    </span>
  );
}

export function Tile({ label, value, hint }) {
  return (
    <div className="tile">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

export function ErrorBox({ error }) {
  if (!error) return null;
  return (
    <div className="alert critical">
      <strong>{error.code || 'Error'}</strong> — {error.message}
    </div>
  );
}

export function Loading({ text = 'Loading…' }) {
  return <p className="muted">{text}</p>;
}

/** Loads data with an async fn; returns [data, error, reload, setData]. */
export function useLoad(fn, deps = []) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const load = useCallback(() => {
    setError(null);
    return fn().then(setData).catch(setError);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => { load(); }, [load]);
  return [data, error, load, setData];
}

// ---------- tooltip
export function useTooltip() {
  const [tip, setTip] = useState(null);
  const show = (e, content) => setTip({ x: e.clientX, y: e.clientY, content });
  const hide = () => setTip(null);
  const node = tip ? (
    <div className="tooltip" style={{ left: Math.min(tip.x + 14, window.innerWidth - 310), top: tip.y + 14 }}>
      {tip.content}
    </div>
  ) : null;
  return { show, hide, node };
}
