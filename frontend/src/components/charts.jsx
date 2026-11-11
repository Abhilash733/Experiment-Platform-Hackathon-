import { useEffect, useRef, useState } from 'react';
import { fmtInt, fmtP, fmtPct, fmtSignedPct, useTooltip } from './ui.jsx';

/** Measures the container so SVG text renders at true pixel size instead of scaling with the viewBox. */
function useWidth(initial = 640) {
  const ref = useRef(null);
  const [w, setW] = useState(initial);
  useEffect(() => {
    if (!ref.current) return undefined;
    const ro = new ResizeObserver(([e]) => setW(Math.max(320, Math.floor(e.contentRect.width))));
    ro.observe(ref.current);
    return () => ro.disconnect();
  }, []);
  return [ref, w];
}

/**
 * Forest plot of relative differences vs control with confidence intervals.
 * rows: [{label, sub, rel, lo, hi, p, significant, tolerance (optional, for guardrails), badDirection: 1|-1}]
 */
export function ForestPlot({ rows, title }) {
  const tip = useTooltip();
  const [ref, W] = useWidth();
  if (!rows?.length) return <p ref={ref} className="muted">No comparisons.</p>;
  const vals = rows.flatMap((r) => [r.lo, r.hi, r.tolerance != null ? r.tolerance * (r.badDirection || 1) : null, 0]).filter((v) => v != null);
  let min = Math.min(...vals), max = Math.max(...vals);
  const pad = (max - min) * 0.12 || 0.05;
  min -= pad; max += pad;
  const rowH = 34, left = Math.min(220, W * 0.32), right = 20, top = 8, H = top + rows.length * rowH + 26;
  const x = (v) => left + ((v - min) / (max - min)) * (W - left - right);
  const ticks = niceTicks(min, max, 5);

  return (
    <div ref={ref}>
      {title && <h3>{title}</h3>}
      <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={title || 'Relative difference vs control'}>
        {ticks.map((t) => (
          <g key={t}>
            <line className="gridline" x1={x(t)} x2={x(t)} y1={top} y2={H - 22} />
            <text x={x(t)} y={H - 6} textAnchor="middle">{fmtSignedPct(t, 0)}</text>
          </g>
        ))}
        <line className="zero" x1={x(0)} x2={x(0)} y1={top} y2={H - 22} />
        {rows.map((r, i) => {
          const cy = top + i * rowH + rowH / 2;
          const tol = r.tolerance != null ? r.tolerance * (r.badDirection || 1) : null;
          return (
            <g key={i}
               onMouseMove={(e) => tip.show(e, (
                 <div>
                   <strong>{r.label}</strong> {r.sub && <span className="muted">{r.sub}</span>}
                   <div>Relative difference: {fmtSignedPct(r.rel, 2)}</div>
                   <div>CI: [{fmtSignedPct(r.lo, 2)}, {fmtSignedPct(r.hi, 2)}]</div>
                   <div>p-value: {fmtP(r.p)} {r.significant ? '(significant)' : '(not significant)'}</div>
                   {tol != null && <div>Tolerance: {fmtSignedPct(tol, 0)}</div>}
                   {r.status && <div>Verdict: {r.status}</div>}
                 </div>
               ))}
               onMouseLeave={tip.hide}>
              <rect x={0} y={cy - rowH / 2} width={W} height={rowH} fill="transparent" />
              <text x={0} y={cy - 2} style={{ fill: 'var(--text)', fontWeight: 600 }}>{r.label}</text>
              <text x={0} y={cy + 12}>{r.sub}</text>
              {tol != null && (
                <line x1={x(tol)} x2={x(tol)} y1={cy - 11} y2={cy + 11} stroke="var(--serious)" strokeWidth="2" strokeDasharray="3 2" />
              )}
              {r.lo != null && (
                <line x1={x(r.lo)} x2={x(r.hi)} y1={cy} y2={cy} stroke="var(--series-1)" strokeWidth="2" strokeLinecap="round" />
              )}
              <circle cx={x(r.rel ?? 0)} cy={cy} r="5" fill="var(--series-1)" stroke="var(--surface)" strokeWidth="2" />
              <text x={x(r.hi ?? r.rel ?? 0) + 8} y={cy + 4} style={{ fill: 'var(--text)' }}>{fmtSignedPct(r.rel, 1)}</text>
            </g>
          );
        })}
      </svg>
      <div className="legend">
        <span><span className="sw" style={{ background: 'var(--series-1)' }} />Estimate with confidence interval</span>
        <span><span className="sw" style={{ background: 'var(--text-2)' }} />0 = no difference vs control</span>
        {rows.some((r) => r.tolerance != null) && <span><span className="sw" style={{ background: 'var(--serious)' }} />Guardrail tolerance</span>}
      </div>
      {tip.node}
    </div>
  );
}

/** Observed vs planned allocation per variant (sample-ratio check). rows: [{label, observed, planned, n}] */
export function AllocationChart({ rows }) {
  const tip = useTooltip();
  const [ref, W] = useWidth();
  if (!rows?.length) return <div ref={ref} />;
  const rowH = 30, left = 150, right = 60, H = rows.length * rowH + 24;
  const max = Math.max(...rows.flatMap((r) => [r.observed, r.planned])) * 1.1;
  const x = (v) => left + (v / max) * (W - left - right);
  return (
    <div ref={ref}>
      <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Observed vs planned allocation">
        {niceTicks(0, max, 4).map((t) => (
          <g key={t}>
            <line className="gridline" x1={x(t)} x2={x(t)} y1={0} y2={H - 20} />
            <text x={x(t)} y={H - 5} textAnchor="middle">{fmtPct(t, 0)}</text>
          </g>
        ))}
        {rows.map((r, i) => {
          const y = i * rowH + 6;
          return (
            <g key={r.label}
               onMouseMove={(e) => tip.show(e, (
                 <div><strong>{r.label}</strong><div>Observed: {fmtPct(r.observed, 2)} ({fmtInt(r.n)} users)</div><div>Planned: {fmtPct(r.planned, 2)}</div>
                 <div>Gap: {fmtSignedPct(r.observed - r.planned, 2)} pts</div></div>
               ))}
               onMouseLeave={tip.hide}>
              <rect x={0} y={y - 4} width={W} height={rowH} fill="transparent" />
              <text x={0} y={y + 12} style={{ fill: 'var(--text)' }}>{r.label}</text>
              <rect x={left} y={y + 2} width={Math.max(0, x(r.observed) - left)} height={14} rx="4" fill="var(--series-1)" />
              <line x1={x(r.planned)} x2={x(r.planned)} y1={y - 2} y2={y + 20} stroke="var(--text)" strokeWidth="2" />
              <text x={Math.max(x(r.observed), x(r.planned)) + 6} y={y + 13} style={{ fill: 'var(--text)' }}>{fmtPct(r.observed, 1)}</text>
            </g>
          );
        })}
      </svg>
      <div className="legend">
        <span><span className="sw" style={{ background: 'var(--series-1)' }} />Observed share</span>
        <span><span className="sw" style={{ background: 'var(--text)', width: 3 }} />Planned share</span>
      </div>
      {tip.node}
    </div>
  );
}

function niceTicks(min, max, count) {
  const span = max - min;
  if (span <= 0) return [min];
  const raw = span / count;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => span / s <= count) || raw;
  const out = [];
  for (let v = Math.ceil(min / step) * step; v <= max + 1e-12; v += step) out.push(Number(v.toFixed(10)));
  return out;
}
