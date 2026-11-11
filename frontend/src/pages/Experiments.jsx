import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { Badge, ErrorBox, Loading, fmtDate, fmtInt, fmtP, fmtSignedPct, useLoad } from '../components/ui.jsx';

export default function Experiments() {
  const [data, error] = useLoad(() => api.experiments());
  const [status, setStatus] = useState('');
  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  const rows = data.filter((e) => !status || e.status === status);
  const statuses = [...new Set(data.map((e) => e.status))];
  return (
    <>
      <div className="page-head">
        <div>
          <h1>Experiments</h1>
          <p className="sub">Every experiment with its latest analysis run and decision.</p>
        </div>
        <div className="row">
          <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Filter by status">
            <option value="">All statuses</option>
            {statuses.map((s) => <option key={s}>{s}</option>)}
          </select>
          <Link to="/design"><button className="primary">New experiment</button></Link>
        </div>
      </div>
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>ID</th><th>Name</th><th>Status</th><th>Enrollment</th><th>Primary metric</th>
                <th className="num">Planned</th><th className="num">Assigned</th><th>SRM p</th><th className="num">Lift (B)</th>
                <th>Recommendation</th><th>Decision</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.experiment_id}>
                  <td className="mono"><Link to={`/experiments/${e.experiment_id}`}>{e.experiment_id}</Link></td>
                  <td>{e.name}</td>
                  <td><Badge value={e.status} /></td>
                  <td className="small">{fmtDate(e.start_at).slice(0, 10)} → {fmtDate(e.end_at).slice(0, 10)}</td>
                  <td className="mono small">{e.primary_metric_def_id}</td>
                  <td className="num">{fmtInt(e.planned_sample_size)}</td>
                  <td className="num">{fmtInt(e.n_assigned_users)}</td>
                  <td>{e.srm_status ? <Badge value={e.srm_status}>{fmtP(e.srm_p_value)}</Badge> : '—'}</td>
                  <td className="num">{fmtSignedPct(e.primary_rel_diff)}</td>
                  <td>{e.recommendation ? <Badge value={e.recommendation} /> : '—'}</td>
                  <td>{e.decision ? <Badge value={e.decision} /> : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
