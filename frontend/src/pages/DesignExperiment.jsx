import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api.js';
import { ErrorBox, Loading, fmtInt, fmtPct, useLoad } from '../components/ui.jsx';

const TEAMS = ['GROWTH', 'MARKETING_OPS', 'SALES_OPS', 'PRODUCT', 'SUPPORT_OPS', 'PLATFORM'];
const iso = (d) => d.toISOString().slice(0, 16);
// Defaults are metric *codes*; the concrete metric_def_id (current version) is resolved from /api/metrics,
// so the form never submits a superseded or non-existent version.
const DEFAULT_PRIMARY_CODE = 'CHECKOUT_CONVERSION';
const DEFAULT_GUARDRAILS = [
  { metricCode: 'UNSUBSCRIBE_RATE', maxRelativeDegradation: 0.15, severity: 'BLOCKING' },
  { metricCode: 'AVG_PAGE_LOAD_MS', maxRelativeDegradation: 0.05, severity: 'BLOCKING' },
];
const currentVersions = (metrics) => (metrics || []).filter((m) => !m.effective_to);
const currentIdForCode = (metrics, code) => currentVersions(metrics).find((m) => m.metric_code === code)?.metric_def_id;

export default function DesignExperiment() {
  const nav = useNavigate();
  const [metrics, mErr] = useLoad(() => api.metrics());
  const start = new Date(Date.now() + 7 * 86400000);
  const [f, setF] = useState({
    name: '', hypothesis: '', ownerTeam: 'MARKETING_OPS', requestedBy: 'marketing.ops.manager',
    primaryMetricDefId: '', startAt: iso(start), endAt: iso(new Date(start.getTime() + 21 * 86400000)),
    baselineRate: 0.12, mdeRelative: 0.15, alpha: 0.05, power: 0.8, srmAlpha: 0.001,
  });
  const [variants, setVariants] = useState([
    { name: 'Control', description: 'Current experience', allocationPct: 50 },
    { name: 'Treatment', description: '', allocationPct: 50 },
  ]);
  const [guardrails, setGuardrails] = useState([]);

  // Once metric versions are loaded, resolve default codes to their current metric_def_id.
  useEffect(() => {
    if (!metrics) return;
    const proportionIds = currentVersions(metrics).filter((m) => m.metric_type === 'PROPORTION').map((m) => m.metric_def_id);
    setF((x) => (x.primaryMetricDefId ? x : { ...x, primaryMetricDefId: currentIdForCode(metrics, DEFAULT_PRIMARY_CODE) || proportionIds[0] || '' }));
    setGuardrails(DEFAULT_GUARDRAILS
      .map(({ metricCode, ...rest }) => ({ ...rest, metricDefId: currentIdForCode(metrics, metricCode) }))
      .filter((g) => g.metricDefId));
  }, [metrics]);
  const [size, setSize] = useState(null);
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (k, v) => setF((x) => ({ ...x, [k]: v }));
  const allocSum = variants.reduce((s, v) => s + Number(v.allocationPct || 0), 0);

  useEffect(() => {
    const minShare = Math.min(...variants.map((v) => Number(v.allocationPct) || 0));
    if (!(f.baselineRate > 0 && f.baselineRate < 1 && f.mdeRelative > 0 && minShare > 0)) { setSize(null); return; }
    const t = setTimeout(() => {
      api.sampleSize({ baseline: f.baselineRate, mde: f.mdeRelative, alpha: f.alpha, power: f.power, arms: variants.length, minSharePct: minShare })
        .then(setSize).catch(() => setSize(null));
    }, 250);
    return () => clearTimeout(t);
  }, [f.baselineRate, f.mdeRelative, f.alpha, f.power, variants]);

  if (mErr) return <ErrorBox error={mErr} />;
  if (!metrics) return <Loading />;
  const current = currentVersions(metrics);
  const proportion = current.filter((m) => m.metric_type === 'PROPORTION');

  const submit = async () => {
    setBusy(true); setErr(null);
    try {
      const body = {
        ...f, startAt: f.startAt + ':00', endAt: f.endAt + ':00',
        baselineRate: Number(f.baselineRate), mdeRelative: Number(f.mdeRelative), alpha: Number(f.alpha), power: Number(f.power), srmAlpha: Number(f.srmAlpha),
        variants: variants.map((v) => ({ ...v, allocationPct: Number(v.allocationPct) })),
        guardrails: guardrails.map((g) => ({ ...g, maxRelativeDegradation: Number(g.maxRelativeDegradation) })),
      };
      const r = await api.createExperiment(body);
      nav(`/experiments/${r.experiment.experiment_id}`);
    } catch (e) { setErr(e); } finally { setBusy(false); }
  };

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Design experiment</h1>
          <p className="sub">Declare the hypothesis, primary metric, guardrails and analysis plan before any data is collected.</p>
        </div>
      </div>
      <ErrorBox error={err} />
      <div className="grid grid-2">
        <div className="card">
          <h2>Hypothesis</h2>
          <div className="form-grid">
            <label className="field">Name<input value={f.name} onChange={(e) => set('name', e.target.value)} placeholder="e.g. Express shipping badge" /></label>
            <label className="field">Owner team<select value={f.ownerTeam} onChange={(e) => set('ownerTeam', e.target.value)}>{TEAMS.map((t) => <option key={t}>{t}</option>)}</select></label>
            <label className="field">Requested by<input value={f.requestedBy} onChange={(e) => set('requestedBy', e.target.value)} /></label>
          </div>
          <label className="field" style={{ marginTop: 12 }}>Hypothesis (at least 20 characters)
            <textarea value={f.hypothesis} onChange={(e) => set('hypothesis', e.target.value)} placeholder="Changing X increases Y by at least Z% without hurting guardrails." />
          </label>
          <h2 style={{ marginTop: 16 }}>Plan</h2>
          <div className="form-grid">
            <label className="field">Primary metric
              <select value={f.primaryMetricDefId} onChange={(e) => set('primaryMetricDefId', e.target.value)}>
                {proportion.map((m) => <option key={m.metric_def_id} value={m.metric_def_id}>{m.name} ({m.metric_def_id})</option>)}
              </select>
            </label>
            <label className="field">Start<input type="datetime-local" value={f.startAt} onChange={(e) => set('startAt', e.target.value)} /></label>
            <label className="field">End<input type="datetime-local" value={f.endAt} onChange={(e) => set('endAt', e.target.value)} /></label>
            <label className="field">Baseline rate<input type="number" step="0.01" value={f.baselineRate} onChange={(e) => set('baselineRate', e.target.value)} /></label>
            <label className="field">MDE (relative)<input type="number" step="0.01" value={f.mdeRelative} onChange={(e) => set('mdeRelative', e.target.value)} /></label>
            <label className="field">Alpha<input type="number" step="0.01" value={f.alpha} onChange={(e) => set('alpha', e.target.value)} /></label>
            <label className="field">Power<input type="number" step="0.05" value={f.power} onChange={(e) => set('power', e.target.value)} /></label>
            <label className="field">SRM alpha<input type="number" step="0.001" value={f.srmAlpha} onChange={(e) => set('srmAlpha', e.target.value)} /></label>
          </div>
        </div>

        <div>
          <div className="card">
            <h2>Variants <span className={`small ${Math.abs(allocSum - 100) > 1e-6 ? 'critical-text' : 'muted'}`}>allocation total {allocSum}%</span></h2>
            {variants.map((v, i) => (
              <div className="row" key={i} style={{ marginBottom: 8 }}>
                <span className="mono" style={{ width: 22 }}>{String.fromCharCode(65 + i)}</span>
                <input style={{ flex: 1 }} value={v.name} placeholder="Name" onChange={(e) => setVariants(variants.map((x, j) => j === i ? { ...x, name: e.target.value } : x))} />
                <input style={{ width: 80 }} type="number" value={v.allocationPct} aria-label="Allocation %" onChange={(e) => setVariants(variants.map((x, j) => j === i ? { ...x, allocationPct: e.target.value } : x))} />%
                {i > 1 && <button className="danger" onClick={() => setVariants(variants.filter((_, j) => j !== i))}>Remove</button>}
              </div>
            ))}
            {variants.length < 5 && <button onClick={() => setVariants([...variants, { name: '', description: '', allocationPct: 0 }])}>Add variant</button>}
            <p className="small muted">A is always the control. Arms beyond B are Bonferroni-corrected.</p>
          </div>
          <div className="card">
            <h2>Guardrails</h2>
            {guardrails.map((g, i) => (
              <div className="row" key={i} style={{ marginBottom: 8 }}>
                <select style={{ flex: 1 }} value={g.metricDefId} onChange={(e) => setGuardrails(guardrails.map((x, j) => j === i ? { ...x, metricDefId: e.target.value } : x))}>
                  {current.map((m) => <option key={m.metric_def_id} value={m.metric_def_id}>{m.name}</option>)}
                </select>
                <input style={{ width: 80 }} type="number" step="0.01" value={g.maxRelativeDegradation} aria-label="Tolerance"
                  onChange={(e) => setGuardrails(guardrails.map((x, j) => j === i ? { ...x, maxRelativeDegradation: e.target.value } : x))} />
                <select value={g.severity} onChange={(e) => setGuardrails(guardrails.map((x, j) => j === i ? { ...x, severity: e.target.value } : x))}>
                  <option>BLOCKING</option><option>ADVISORY</option>
                </select>
                <button className="danger" onClick={() => setGuardrails(guardrails.filter((_, j) => j !== i))}>Remove</button>
              </div>
            ))}
            <button onClick={() => setGuardrails([...guardrails, { metricDefId: currentIdForCode(metrics, 'SUPPORT_TICKET_RATE') || current[0]?.metric_def_id, maxRelativeDegradation: 0.15, severity: 'ADVISORY' }])}>Add guardrail</button>
          </div>
          <div className="card">
            <h2>Sample size</h2>
            {size ? (
              <dl className="kv">
                <dt>Per arm</dt><dd><strong>{fmtInt(size.per_arm)}</strong> users</dd>
                <dt>Total</dt><dd><strong>{fmtInt(size.total)}</strong> users</dd>
                <dt>Detects</dt><dd>{fmtPct(f.baselineRate, 1)} → {fmtPct(f.baselineRate * (1 + Number(f.mdeRelative)), 1)}</dd>
                <dt>Alpha used</dt><dd>{size.alpha_adjusted.toFixed(4)}</dd>
              </dl>
            ) : <p className="muted">Enter a valid baseline, MDE and allocations.</p>}
            <p className="small muted">{size?.method}</p>
            <button className="primary" disabled={busy} onClick={submit}>{busy ? 'Saving…' : 'Create experiment (DRAFT)'}</button>
          </div>
        </div>
      </div>
    </>
  );
}
