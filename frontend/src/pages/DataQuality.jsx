import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { Badge, ErrorBox, Loading, Tile, fmtDate, fmtInt, fmtP, fmtPct, fmtSignedPct, useLoad } from '../components/ui.jsx';

export default function DataQuality() {
  const [v, error, reload] = useLoad(() => api.validation());
  const [gt] = useLoad(() => api.groundTruth());
  const [busy, setBusy] = useState(false);
  const [cat, setCat] = useState('');
  const [issueType, setIssueType] = useState('');
  const [issues, setIssues] = useState(null);

  if (error) return <ErrorBox error={error} />;
  if (!v) return <Loading />;
  if (!v.checks) return <div className="card"><p>{v.message}</p></div>;
  const ev = v.evaluation || {};
  const cats = [...new Set(v.checks.map((c) => c.category))];
  const shown = v.checks.filter((c) => !cat || c.category === cat);
  const byCat = cats.map((c) => {
    const rows = v.checks.filter((x) => x.category === c);
    return { c, total: rows.length, fail: rows.filter((x) => x.status === 'FAIL').length, warn: rows.filter((x) => x.status === 'WARN').length };
  });

  const rerun = async () => { setBusy(true); try { await api.validate(); await reload(); } finally { setBusy(false); } };
  const loadIssues = async (type) => { setIssueType(type); setIssues(null); setIssues(await api.issues({ type, limit: 200 })); };

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Data quality & ground truth</h1>
          <p className="sub">Validation {v.validation_run_id} · {fmtDate(v.run_at)}</p>
        </div>
        <button className="primary" disabled={busy} onClick={rerun}>{busy ? 'Running…' : 'Run validation'}</button>
      </div>

      <div className="tiles">
        <Tile label="Checks passed" value={fmtInt(v.passed)} />
        <Tile label="Integrity failures" value={fmtInt(v.failed)} hint="must be 0" />
        <Tile label="Warnings" value={fmtInt(v.warnings)} hint="explained below" />
        <Tile label="Detector precision" value={fmtPct(ev.detection_overall?.precision, 1)} />
        <Tile label="Detector recall" value={fmtPct(ev.detection_overall?.recall, 1)} />
        <Tile label="Recommendation accuracy" value={ev.recommendation_total ? `${ev.recommendation_correct}/${ev.recommendation_total}` : '—'} />
        <Tile label="CI coverage" value={ev.ci_total ? `${ev.ci_covered}/${ev.ci_total}` : '—'} hint="nominal 95%" />
      </div>

      <div className="card">
        <h2>Anomaly detection vs ground truth</h2>
        <p className="small text-2">Detectors read only source tables. Ground-truth labels come from the generator's documented rules and are used here for scoring only.</p>
        <table>
          <thead><tr><th>Issue type</th><th className="num">Ground truth</th><th className="num">Detected</th><th className="num">TP</th><th className="num">FP</th><th className="num">FN</th><th className="num">Precision</th><th className="num">Recall</th><th></th></tr></thead>
          <tbody>
            {(ev.detection || []).map((d) => (
              <tr key={d.issue_type}>
                <td>{d.issue_type.replaceAll('_', ' ')}</td>
                <td className="num">{fmtInt(d.ground_truth)}</td><td className="num">{fmtInt(d.detected)}</td>
                <td className="num">{fmtInt(d.true_positive)}</td><td className="num">{fmtInt(d.false_positive)}</td><td className="num">{fmtInt(d.false_negative)}</td>
                <td className="num">{fmtPct(d.precision, 1)}</td><td className="num">{fmtPct(d.recall, 1)}</td>
                <td><button className="small" onClick={() => loadIssues(d.issue_type)}>Rows</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        {ev.undetectable && (
          <p className="small muted">{ev.undetectable.issue_type}: {fmtInt(ev.undetectable.ground_truth)} labelled, {fmtInt(ev.undetectable.assignments_without_exposure)} assignments have no exposure. {ev.undetectable.note}</p>
        )}
        {issueType && (
          <div style={{ marginTop: 12 }}>
            <h3>{issueType.replaceAll('_', ' ')} — first 200 detected rows</h3>
            {!issues ? <Loading /> : (
              <div className="table-wrap" style={{ maxHeight: 300 }}>
                <table>
                  <thead><tr><th>Entity</th><th>ID</th><th>Experiment</th><th>Details</th></tr></thead>
                  <tbody>{issues.map((i) => (
                    <tr key={i.issue_id}><td className="small">{i.entity_type}</td><td className="mono small">{i.entity_id}</td>
                      <td className="mono small"><Link to={`/experiments/${i.experiment_id}`}>{i.experiment_id}</Link></td><td className="small">{i.details}</td></tr>
                  ))}</tbody>
                </table>
              </div>
            )}
          </div>
        )}
      </div>

      <div className="card">
        <h2>Engine decisions vs expected outcome</h2>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Experiment</th><th>Scenario</th><th>Expected</th><th>Engine</th><th>Match</th><th>Arm</th><th className="num">True ITT lift</th><th className="num">Estimate</th><th className="num">CI</th><th className="num">p</th><th>Covers</th></tr></thead>
            <tbody>
              {(ev.experiments || []).flatMap((e) => (e.arms.length ? e.arms : [{}]).map((a, i) => (
                <tr key={e.experiment_id + i}>
                  {i === 0 && <>
                    <td rowSpan={e.arms.length || 1} className="small"><Link className="mono" to={`/experiments/${e.experiment_id}`}>{e.experiment_id}</Link><div className="text-2">{e.name}</div></td>
                    <td rowSpan={e.arms.length || 1} className="small">{e.scenario}</td>
                    <td rowSpan={e.arms.length || 1} className="small mono">{e.expected}</td>
                    <td rowSpan={e.arms.length || 1} className="small mono">{e.actual}</td>
                    <td rowSpan={e.arms.length || 1}><Badge value={String(e.match)}>{e.match ? 'yes' : 'no'}</Badge></td>
                  </>}
                  <td className="mono small">{a.variant_id?.slice(-1)}</td>
                  <td className="num">{fmtSignedPct(a.true_lift)}</td>
                  <td className="num">{fmtSignedPct(a.rel_diff)}</td>
                  <td className="num">{a.rel_ci_low == null ? '—' : `[${fmtSignedPct(a.rel_ci_low)}, ${fmtSignedPct(a.rel_ci_high)}]`}</td>
                  <td className="num">{fmtP(a.p_value)}</td>
                  <td>{a.variant_id ? <Badge value={String(a.ci_covers_truth)}>{a.ci_covers_truth ? 'yes' : 'no'}</Badge> : null}</td>
                </tr>
              )))}
            </tbody>
          </table>
        </div>
        <p className="small muted">A 95% interval is expected to miss the truth about 1 time in 20, so an occasional "no" is consistent with correct calibration.</p>
      </div>

      <div className="grid grid-2">
        <div className="card">
          <h2>Checks by category</h2>
          <table>
            <thead><tr><th>Category</th><th className="num">Checks</th><th className="num">Fail</th><th className="num">Warn</th></tr></thead>
            <tbody>{byCat.map((b) => (
              <tr key={b.c} onClick={() => setCat(b.c)} style={{ cursor: 'pointer' }}>
                <td>{b.c.replaceAll('_', ' ')}</td><td className="num">{b.total}</td><td className="num">{b.fail}</td><td className="num">{b.warn}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
        <div className="card">
          <h2>Ground-truth labels</h2>
          <table>
            <thead><tr><th>Label</th><th>Scope</th><th>Entity</th><th className="num">Rows</th></tr></thead>
            <tbody>{(gt || []).map((g) => (
              <tr key={g.issue_type + g.entity_type}><td className="small">{g.issue_type}</td><td className="small">{g.scope}</td><td className="small">{g.entity_type}</td><td className="num">{fmtInt(g.n)}</td></tr>
            ))}</tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h2>All checks</h2>
          <select value={cat} onChange={(e) => setCat(e.target.value)} aria-label="Filter category">
            <option value="">All categories</option>
            {cats.map((c) => <option key={c}>{c}</option>)}
          </select>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Category</th><th>Check</th><th>Target</th><th>Status</th><th className="num">Violations</th><th className="num">Expected</th><th>Details</th></tr></thead>
            <tbody>{shown.map((c, i) => (
              <tr key={i}><td className="small">{c.category}</td><td>{c.check_name}</td><td className="mono small">{c.target}</td>
                <td><Badge value={c.status} /></td><td className="num">{fmtInt(c.violations)}</td><td className="num">{c.expected ?? '—'}</td><td className="small">{c.details}</td></tr>
            ))}</tbody>
          </table>
        </div>
      </div>
    </>
  );
}
