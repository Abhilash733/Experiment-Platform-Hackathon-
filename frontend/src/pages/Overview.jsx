import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { Badge, ErrorBox, Loading, Tile, fmtDate, fmtInt, fmtPct, fmtSignedPct, useLoad } from '../components/ui.jsx';

export default function Overview() {
  const [data, error] = useLoad(() => api.overview());
  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  const c = data.row_counts;
  const v = data.latest_validation || {};
  const ev = v.evaluation || {};
  const exps = data.experiments || [];
  const srmFails = exps.filter((e) => e.srm_status === 'FAIL').length;
  const grFails = exps.filter((e) => e.guardrail_fails > 0).length;
  const srmIgnored = exps.filter((e) => e.srm_status === 'FAIL' && e.decision === 'SHIP' && !e.srm_acknowledged);

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Overview</h1>
          <p className="sub">Experiments, randomization health, guardrails and data quality at a glance.</p>
        </div>
        {data.latest_generation && (
          <span className="small muted">
            Dataset {data.latest_generation.gen_run_id} · seed {data.latest_generation.seed} · generated {fmtDate(data.latest_generation.finished_at)}
          </span>
        )}
      </div>

      {srmIgnored.map((e) => (
        <div key={e.experiment_id} className="alert critical">
          <strong>Exception: SRM ignored.</strong> {e.experiment_id} ({e.name}) was shipped although its sample-ratio check failed and nobody acknowledged it.{' '}
          <Link to={`/experiments/${e.experiment_id}`}>Review</Link>
        </div>
      ))}

      <div className="tiles">
        <Tile label="Experiments" value={fmtInt(c.experiment)} hint={`${fmtInt(c.variant)} variants`} />
        <Tile label="Eligible users" value={fmtInt(c.audience_member)} />
        <Tile label="Assignments" value={fmtInt(c.assignment)} />
        <Tile label="Exposures" value={fmtInt(c.exposure_event)} />
        <Tile label="Outcome events" value={fmtInt(c.conversion_event)} />
        <Tile label="SRM failures" value={srmFails} hint="experiments" />
        <Tile label="Guardrail failures" value={grFails} hint="experiments" />
        <Tile label="Integrity checks" value={v.failed === 0 ? 'All pass' : `${v.failed ?? '—'} failed`} hint={`${v.passed ?? 0} passed · ${v.warnings ?? 0} warnings`} />
      </div>

      <div className="card">
        <h2>Experiments</h2>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>ID</th><th>Name</th><th>Status</th><th className="num">Users</th><th>SRM</th>
                <th className="num">Primary lift (B)</th><th>Guardrails</th><th>Engine says</th><th>Decision</th>
              </tr>
            </thead>
            <tbody>
              {exps.map((e) => (
                <tr key={e.experiment_id}>
                  <td className="mono"><Link to={`/experiments/${e.experiment_id}`}>{e.experiment_id}</Link></td>
                  <td>{e.name}<div className="small muted">{e.owner_team}</div></td>
                  <td><Badge value={e.status} /></td>
                  <td className="num">{fmtInt(e.n_assigned_users)}</td>
                  <td>{e.srm_status ? <Badge value={e.srm_status} /> : '—'}</td>
                  <td className="num">{fmtSignedPct(e.primary_rel_diff)}</td>
                  <td>
                    {e.guardrail_fails > 0 ? <Badge value="FAIL">{e.guardrail_fails} fail</Badge>
                      : e.guardrail_warns > 0 ? <Badge value="WARN">{e.guardrail_warns} warn</Badge>
                      : e.run_id ? <Badge value="PASS" /> : '—'}
                  </td>
                  <td>{e.recommendation ? <Badge value={e.recommendation} /> : '—'}</td>
                  <td>{e.decision ? <Badge value={e.decision} /> : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="grid grid-2">
        <div className="card">
          <h2>Detected issues (latest validation)</h2>
          <table>
            <thead><tr><th>Issue type</th><th className="num">Rows / entities</th></tr></thead>
            <tbody>
              {(data.issue_counts || []).map((i) => (
                <tr key={i.issue_type}><td>{i.issue_type.replaceAll('_', ' ')}</td><td className="num">{fmtInt(i.n)}</td></tr>
              ))}
            </tbody>
          </table>
          <p className="small"><Link to="/quality">Open data quality & ground-truth evaluation</Link></p>
        </div>
        <div className="card">
          <h2>Sanity check against ground truth</h2>
          <dl className="kv">
            <dt>Engine recommendation accuracy</dt>
            <dd>{ev.recommendation_total ? `${ev.recommendation_correct}/${ev.recommendation_total} (${fmtPct(ev.recommendation_accuracy, 0)})` : '—'}</dd>
            <dt>CI coverage of true lift</dt>
            <dd>{ev.ci_total ? `${ev.ci_covered}/${ev.ci_total} arms (${fmtPct(ev.ci_coverage, 0)})` : '—'}</dd>
            <dt>Anomaly detection precision</dt>
            <dd>{fmtPct(ev.detection_overall?.precision, 1)}</dd>
            <dt>Anomaly detection recall</dt>
            <dd>{fmtPct(ev.detection_overall?.recall, 1)}</dd>
          </dl>
          <p className="small muted">Ground truth is stored separately and never read by the analysis engine or detectors.</p>
        </div>
      </div>
    </>
  );
}
