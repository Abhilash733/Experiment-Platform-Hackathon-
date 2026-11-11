import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api.js';
import { AllocationChart, ForestPlot } from '../components/charts.jsx';
import {
  Badge, ErrorBox, Loading, Tile, fmtDate, fmtInt, fmtMetric, fmtNum, fmtP, fmtPct, fmtSignedPct, useLoad,
} from '../components/ui.jsx';

const NEXT = { DRAFT: ['DESIGNED', 'CANCELLED'], DESIGNED: ['RUNNING', 'CANCELLED'], RUNNING: ['STOPPED', 'CANCELLED'], DECIDED: ['ARCHIVED'] };

export default function ExperimentDetail() {
  const { id } = useParams();
  const [data, error, reload, setData] = useLoad(() => api.experiment(id), [id]);
  const [tab, setTab] = useState('results');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState(null);
  const [notice, setNotice] = useState(null);

  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  const e = data.experiment;
  const run = data.latest_run;
  const results = data.latest_results || [];
  const control = data.variants.find((v) => v.is_control);

  const act = async (fn, msg) => {
    setBusy(true); setActionError(null); setNotice(null);
    try { const r = await fn(); setNotice(typeof msg === 'function' ? msg(r) : msg); await reload(); }
    catch (err) { setActionError(err); }
    finally { setBusy(false); }
  };

  const primary = results.filter((r) => r.metric_role === 'PRIMARY');
  const guard = results.filter((r) => r.metric_role === 'GUARDRAIL');
  const forestPrimary = primary.filter((r) => !r.is_control).map((r) => ({
    label: `${r.variant_key} · ${r.variant_name}`, sub: `${fmtMetric(r.metric_value, r.unit)} vs ${fmtMetric(r.control_value, r.unit)} control`,
    rel: r.rel_diff, lo: r.rel_ci_low, hi: r.rel_ci_high, p: r.p_value, significant: r.is_significant, status: r.arm_decision,
  }));
  const forestGuard = guard.filter((r) => !r.is_control).map((r) => ({
    label: `${r.variant_key} · ${r.metric_code.replaceAll('_', ' ').toLowerCase()}`, sub: `${r.severity} · tolerance ${fmtPct(r.max_relative_degradation, 0)}`,
    rel: r.rel_diff, lo: r.rel_ci_low, hi: r.rel_ci_high, p: r.p_value, significant: r.is_significant,
    tolerance: r.max_relative_degradation, badDirection: r.desired_direction === 'DECREASE' ? 1 : -1, status: r.guardrail_status,
  }));
  const allocation = run ? primary.map((r) => {
    const v = data.variants.find((x) => x.variant_id === r.variant_id);
    return { label: `${r.variant_key} · ${r.variant_name}`, observed: r.n_users / run.n_analyzed_users, planned: v.planned_allocation_pct / 100, n: r.n_users };
  }) : [];
  const newerMetric = data.metric_versions.filter((m) => m.version > data.primary_metric.version);
  const lastDecision = data.decisions[0];

  return (
    <>
      <div className="page-head">
        <div>
          <div className="small muted"><Link to="/experiments">Experiments</Link> / <span className="mono">{e.experiment_id}</span></div>
          <h1>{e.name}</h1>
          <div className="row"><Badge value={e.status} />{run && <Badge value={run.recommendation}>Engine: {run.recommendation.replaceAll('_', ' ')}</Badge>}
            {lastDecision && <Badge value={lastDecision.decision}>Decision: {lastDecision.decision.replaceAll('_', ' ')}</Badge>}</div>
        </div>
        <div className="row">
          {(NEXT[e.status] || []).map((s) => (
            <button key={s} disabled={busy} className={s === 'CANCELLED' ? 'danger' : ''}
              onClick={() => act(() => api.transition(id, { toStatus: s, changedBy: 'ui.analyst', reason: 'Changed in UI' }), `Status changed to ${s}`)}>
              → {s}
            </button>
          ))}
          {['STOPPED', 'ANALYZED', 'DECIDED'].includes(e.status) && (
            <button className="primary" disabled={busy} onClick={() => act(() => api.analyze(id, 'ui.analyst'), (r) => `Analysis ${r.run_id} completed: ${r.recommendation}`)}>
              {busy ? 'Working…' : 'Re-run analysis'}
            </button>
          )}
        </div>
      </div>

      <ErrorBox error={actionError} />
      {notice && <div className="alert good">{notice}</div>}
      <Exceptions data={data} run={run} lastDecision={lastDecision} newerMetric={newerMetric} />

      <div className="tiles">
        <Tile label="Eligible" value={fmtInt(data.counts.eligible_users)} />
        <Tile label="Assigned users" value={fmtInt(data.counts.assigned_users)} hint={`${fmtInt(data.counts.assignments)} assignment rows`} />
        <Tile label="Analysed (ITT)" value={fmtInt(run?.n_analyzed_users)} hint={run ? `${run.n_excluded_multi_assignment} excluded: multi-variant` : ''} />
        <Tile label="Exposures" value={fmtInt(data.counts.exposures)} hint={run ? `${fmtInt(run.n_never_exposed_users)} users never exposed` : ''} />
        <Tile label="Planned sample" value={fmtInt(e.planned_sample_size)} hint={`α=${e.alpha}, power=${e.power}, MDE=${fmtPct(e.mde_relative, 0)}`} />
      </div>

      <div className="tabs">
        {['results', 'randomization', 'design', 'decision', 'history', 'issues'].map((t) => (
          <button key={t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>{t[0].toUpperCase() + t.slice(1)}</button>
        ))}
      </div>

      {tab === 'results' && (
        !run ? <div className="card"><p className="muted">No analysis yet. Results appear after the experiment is STOPPED and analysed.</p></div> : (
          <>
            <div className="card">
              <h2>Primary metric · {data.primary_metric.name} <span className="mono small muted">{run.primary_metric_def_id}</span></h2>
              <ForestPlot rows={forestPrimary} />
              <ResultTable rows={primary} />
              <p className="small muted">Two-proportion z-test (pooled) with unpooled {fmtPct(1 - (primary.find((r) => !r.is_control)?.alpha_applied ?? 0.05), 1)} CI{e.multiple_testing === 'BONFERRONI' ? ', Bonferroni-corrected across treatment arms' : ''}. Window {data.primary_metric.window_days} days from assignment; data cutoff {fmtDate(run.data_cutoff_at)}.</p>
            </div>
            <div className="card">
              <h2>Guardrails</h2>
              <ForestPlot rows={forestGuard} />
              <ResultTable rows={guard} guardrail />
              <p className="small muted">FAIL = the 95% CI shows degradation beyond tolerance; WARN = the point estimate is beyond tolerance, or the degradation is significant but within tolerance. Only BLOCKING guardrails block shipping.</p>
            </div>
            <div className="card">
              <h3>Decision trace</h3>
              <p className="mono small" style={{ whiteSpace: 'pre-wrap' }}>{run.notes}</p>
            </div>
          </>
        )
      )}

      {tab === 'randomization' && run && (
        <div className="grid grid-2">
          <div className="card">
            <h2>Sample ratio check</h2>
            <AllocationChart rows={allocation} />
            <dl className="kv" style={{ marginTop: 12 }}>
              <dt>Chi-square</dt><dd>{fmtNum(run.srm_chi2, 2)} (df {run.srm_df})</dd>
              <dt>p-value</dt><dd>{fmtP(run.srm_p_value)} vs threshold {e.srm_alpha}</dd>
              <dt>Verdict</dt><dd><Badge value={run.srm_status} /></dd>
            </dl>
          </div>
          <div className="card">
            <h2>Covariate balance & data health</h2>
            <dl className="kv">
              <dt>Platform mix (chi-square p)</dt><dd>{fmtP(run.balance_platform_p)}</dd>
              <dt>Pre-period sessions |SMD|</dt><dd>{fmtNum(run.balance_sessions_smd, 3)} (warn &gt; 0.1)</dd>
              <dt>Balance verdict</dt><dd><Badge value={run.balance_status} /></dd>
              <dt>Randomization</dt><dd className="mono small">{e.allocation_method}, seed {e.randomization_seed}, unit {e.randomization_unit}</dd>
              <dt>Multi-variant users excluded</dt><dd>{fmtInt(run.n_excluded_multi_assignment)}</dd>
              <dt>Exposures before assignment</dt><dd>{fmtInt(run.n_exposure_before_assignment)}</dd>
              <dt>Noncompliant users</dt><dd>{fmtInt(run.n_noncompliant_users)}</dd>
              <dt>Never-exposed users</dt><dd>{fmtInt(run.n_never_exposed_users)}</dd>
              <dt>Exposures missing telemetry</dt><dd>{fmtInt(run.n_missing_telemetry_exposures)}</dd>
              <dt>Out-of-window "in window" events</dt><dd>{fmtInt(run.n_out_of_window_reported)}</dd>
            </dl>
          </div>
        </div>
      )}

      {tab === 'design' && (
        <div className="grid grid-2">
          <div className="card">
            <h2>Hypothesis & analysis plan</h2>
            <p>{e.hypothesis}</p>
            <dl className="kv">
              <dt>Owner</dt><dd>{e.owner_team} · requested by {e.requested_by}</dd>
              <dt>Primary metric</dt><dd className="mono">{e.primary_metric_def_id}</dd>
              <dt>Definition</dt><dd className="small">{data.primary_metric.description}</dd>
              <dt>Enrollment</dt><dd>{fmtDate(e.start_at)} → {fmtDate(e.end_at)}</dd>
              <dt>Plan locked</dt><dd>{fmtDate(e.analysis_plan_locked_at)}</dd>
              <dt>Population</dt><dd>{e.analysis_population} · correction {e.multiple_testing}</dd>
              <dt>α / power / MDE</dt><dd>{e.alpha} / {e.power} / {fmtPct(e.mde_relative, 0)} (baseline {fmtPct(e.baseline_rate, 1)})</dd>
              <dt>SRM threshold</dt><dd>p &lt; {e.srm_alpha}</dd>
            </dl>
          </div>
          <div className="card">
            <h2>Variants</h2>
            <table>
              <thead><tr><th>Key</th><th>Name</th><th className="num">Planned</th><th className="num">Assigned</th><th className="num">Exposures</th></tr></thead>
              <tbody>{data.variants.map((v) => (
                <tr key={v.variant_id}><td className="mono">{v.variant_key}{v.is_control ? ' (control)' : ''}</td><td>{v.name}<div className="small muted">{v.description}</div></td>
                  <td className="num">{v.planned_allocation_pct}%</td><td className="num">{fmtInt(v.n_assigned_users)}</td><td className="num">{fmtInt(v.n_exposures)}</td></tr>
              ))}</tbody>
            </table>
            <h2 style={{ marginTop: 18 }}>Guardrails</h2>
            <table>
              <thead><tr><th>Metric</th><th className="num">Tolerance</th><th>Severity</th></tr></thead>
              <tbody>{data.guardrails.map((g) => (
                <tr key={g.guardrail_id}><td>{g.metric_name}<div className="mono small muted">{g.metric_def_id}</div></td>
                  <td className="num">{fmtPct(g.max_relative_degradation, 0)}</td><td>{g.severity}</td></tr>
              ))}</tbody>
            </table>
            <h2 style={{ marginTop: 18 }}>Primary metric versions</h2>
            <table>
              <thead><tr><th>Version</th><th className="num">Window</th><th>Effective from</th><th>Reason</th></tr></thead>
              <tbody>{data.metric_versions.map((m) => (
                <tr key={m.metric_def_id}><td className="mono">{m.metric_def_id}</td><td className="num">{m.window_days} d</td><td>{fmtDate(m.effective_from)}</td><td className="small">{m.change_reason}</td></tr>
              ))}</tbody>
            </table>
          </div>
        </div>
      )}

      {tab === 'decision' && (
        <div className="grid grid-2">
          <DecisionForm data={data} run={run} control={control} onDone={(r) => { setData(r.experiment); setNotice(`Decision ${r.decision_id} recorded${r.warnings.length ? ' — ' + r.warnings.join('; ') : ''}`); }} />
          <div className="card">
            <h2>Decision log</h2>
            {data.decisions.length === 0 ? <p className="muted">No decisions yet.</p> : (
              <table>
                <thead><tr><th>ID</th><th>Decision</th><th>Run</th><th>By / at</th><th>Acks</th></tr></thead>
                <tbody>{data.decisions.map((d) => (
                  <tr key={d.decision_id}>
                    <td className="mono small">{d.decision_id}</td>
                    <td><Badge value={d.decision} />{d.chosen_variant_id && <div className="mono small">{d.chosen_variant_id}</div>}<div className="small text-2">{d.rationale}</div></td>
                    <td className="mono small">{d.run_id}</td>
                    <td className="small">{d.decided_by}<br />{fmtDate(d.decided_at)}</td>
                    <td className="small">SRM: {d.srm_acknowledged ? 'yes' : 'no'}<br />Guardrails: {d.guardrails_acknowledged ? 'yes' : 'no'}</td>
                  </tr>
                ))}</tbody>
              </table>
            )}
          </div>
        </div>
      )}

      {tab === 'history' && (
        <div className="grid grid-2">
          <div className="card">
            <h2>Lifecycle (append-only)</h2>
            <ul className="timeline">
              {data.status_history.map((s) => (
                <li key={s.status_event_id}>
                  <strong>{s.from_status ?? '∅'} → {s.to_status}</strong> <span className="small muted">{fmtDate(s.changed_at)} · {s.changed_by}</span>
                  <div className="small text-2">{s.reason}</div>
                </li>
              ))}
            </ul>
          </div>
          <div className="card">
            <h2>Analysis runs</h2>
            <table>
              <thead><tr><th>Run</th><th>Computed</th><th>By</th><th>SRM</th><th>Result</th></tr></thead>
              <tbody>{data.runs.map((r) => (
                <tr key={r.run_id}><td className="mono small">{r.run_id}</td><td className="small">{fmtDate(r.computed_at)}</td><td className="small">{r.triggered_by}</td>
                  <td><Badge value={r.srm_status} /></td><td><Badge value={r.recommendation} /></td></tr>
              ))}</tbody>
            </table>
            <p className="small muted">Re-running an analysis appends a new run; earlier runs are never overwritten.</p>
          </div>
        </div>
      )}

      {tab === 'issues' && (
        <div className="card">
          <h2>Detected data issues for this experiment</h2>
          {data.issues.length === 0 ? <p className="muted">None detected.</p> : (
            <table>
              <thead><tr><th>Issue type</th><th className="num">Count</th><th>Sample entity IDs</th><th>Example</th></tr></thead>
              <tbody>{data.issues.map((i) => (
                <tr key={i.issue_type}><td>{i.issue_type.replaceAll('_', ' ')}</td><td className="num">{fmtInt(i.n)}</td>
                  <td className="mono small">{i.sample_ids?.split(',').join(', ')}</td><td className="small">{i.example}</td></tr>
              ))}</tbody>
            </table>
          )}
        </div>
      )}
    </>
  );
}

function Exceptions({ data, run, lastDecision, newerMetric }) {
  const out = [];
  if (run?.srm_status === 'FAIL') {
    out.push(<div key="srm" className="alert critical"><strong>Sample-ratio mismatch</strong> (p = {fmtP(run.srm_p_value)}). The observed split differs from the planned allocation, so the effect estimates are not trustworthy.</div>);
    if (lastDecision?.decision === 'SHIP' && !lastDecision.srm_acknowledged) {
      out.push(<div key="ign" className="alert critical"><strong>SRM ignored:</strong> decision {lastDecision.decision_id} shipped {lastDecision.chosen_variant_id} without acknowledging the SRM failure.</div>);
    }
  }
  const fails = (data.latest_results || []).filter((r) => r.guardrail_status === 'FAIL');
  if (fails.length) out.push(<div key="gr" className="alert warning"><strong>Guardrail regression:</strong> {fails.map((f) => `${f.variant_key} ${f.metric_code} ${fmtSignedPct(f.rel_diff)} (${f.severity})`).join(', ')}.</div>);
  if (newerMetric.length) {
    out.push(<div key="met" className="alert warning"><strong>Metric definition changed after results:</strong> the plan pins {data.primary_metric.metric_def_id}; {newerMetric.map((m) => `${m.metric_def_id} (${m.window_days}-day window) is effective from ${fmtDate(m.effective_from)}`).join(', ')}. Results keep the predeclared version.</div>);
  }
  if (run?.balance_status === 'WARN') out.push(<div key="bal" className="alert warning"><strong>Covariate imbalance:</strong> platform p = {fmtP(run.balance_platform_p)}, sessions SMD = {fmtNum(run.balance_sessions_smd, 3)}.</div>);
  return out;
}

function ResultTable({ rows, guardrail }) {
  return (
    <div className="table-wrap" style={{ marginTop: 10 }}>
      <table>
        <thead>
          <tr>
            {guardrail && <th>Metric</th>}<th>Variant</th><th className="num">Users</th><th className="num">Value</th>
            <th className="num">Δ abs</th><th className="num">Δ rel</th><th className="num">CI (rel)</th><th className="num">p</th><th>{guardrail ? 'Verdict' : 'Arm decision'}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.result_id}>
              {guardrail && <td className="small">{r.metric_code}</td>}
              <td>{r.variant_key} · {r.variant_name}</td>
              <td className="num">{fmtInt(r.n_users)}</td>
              <td className="num">{fmtMetric(r.metric_value, r.unit)}</td>
              <td className="num">{r.abs_diff == null ? '—' : r.unit === 'ms' ? `${fmtNum(r.abs_diff, 1)} ms` : `${fmtNum(r.abs_diff * 100, 2)} pts`}</td>
              <td className="num">{fmtSignedPct(r.rel_diff)}</td>
              <td className="num">{r.rel_ci_low == null ? '—' : `[${fmtSignedPct(r.rel_ci_low)}, ${fmtSignedPct(r.rel_ci_high)}]`}</td>
              <td className="num">{fmtP(r.p_value)}</td>
              <td>{guardrail ? (r.guardrail_status ? <Badge value={r.guardrail_status} /> : <span className="muted">control</span>) : (r.arm_decision ? <Badge value={r.arm_decision} /> : <span className="muted">control</span>)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DecisionForm({ data, run, onDone }) {
  const treatments = data.variants.filter((v) => !v.is_control);
  const [form, setForm] = useState({
    decision: 'SHIP', chosenVariantId: run?.recommended_variant_id || treatments[0]?.variant_id, decidedBy: 'marketing.ops.lead',
    srmAcknowledged: false, guardrailsAcknowledged: false, rationale: '',
  });
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);
  const status = data.experiment.status;
  if (!['ANALYZED', 'DECIDED'].includes(status)) {
    return <div className="card"><h2>Record decision</h2><p className="muted">Available once the experiment is ANALYZED (current: {status}).</p></div>;
  }
  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));
  const submit = async () => {
    setBusy(true); setErr(null);
    try { onDone(await api.decide(data.experiment.experiment_id, form)); } catch (e) { setErr(e); } finally { setBusy(false); }
  };
  return (
    <div className="card">
      <h2>Record decision</h2>
      <p className="small text-2">Based on latest run <span className="mono">{run?.run_id}</span> · engine recommends <Badge value={run?.recommendation} /></p>
      <ErrorBox error={err} />
      <div className="form-grid">
        <label className="field">Decision
          <select value={form.decision} onChange={(e) => set('decision', e.target.value)}>
            {['SHIP', 'DO_NOT_SHIP', 'ITERATE', 'ROLLBACK'].map((d) => <option key={d}>{d}</option>)}
          </select>
        </label>
        {form.decision === 'SHIP' && (
          <label className="field">Variant to ship
            <select value={form.chosenVariantId} onChange={(e) => set('chosenVariantId', e.target.value)}>
              {treatments.map((v) => <option key={v.variant_id} value={v.variant_id}>{v.variant_key} · {v.name}</option>)}
            </select>
          </label>
        )}
        <label className="field">Decided by
          <input value={form.decidedBy} onChange={(e) => set('decidedBy', e.target.value)} />
        </label>
      </div>
      <div className="row" style={{ margin: '12px 0' }}>
        <label className="small"><input type="checkbox" checked={form.srmAcknowledged} onChange={(e) => set('srmAcknowledged', e.target.checked)} /> I acknowledge an SRM failure</label>
        <label className="small"><input type="checkbox" checked={form.guardrailsAcknowledged} onChange={(e) => set('guardrailsAcknowledged', e.target.checked)} /> I acknowledge guardrail failures</label>
      </div>
      <label className="field">Rationale<textarea value={form.rationale} onChange={(e) => set('rationale', e.target.value)} /></label>
      <div className="row" style={{ marginTop: 12 }}>
        <button className="primary" disabled={busy} onClick={submit}>{busy ? 'Saving…' : 'Record decision'}</button>
        <span className="small muted">Shipping on a failed SRM or blocking guardrail is rejected unless acknowledged.</span>
      </div>
    </div>
  );
}
