import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { ErrorBox, Loading, fmtInt, useLoad } from '../components/ui.jsx';

const TABLES = ['experiment', 'variant', 'metric_definition', 'guardrail_metric', 'audience_member', 'experiment_eligibility', 'assignment',
  'exposure_event', 'conversion_event', 'experiment_status_event', 'analysis_run', 'experiment_result', 'experiment_decision', 'ground_truth'];
const PAGE = 50;

export default function DataExplorer() {
  const [table, setTable] = useState('assignment');
  const [exp, setExp] = useState('');
  const [offset, setOffset] = useState(0);
  const [exps] = useLoad(() => api.experiments());
  const [data, error, reload] = useLoad(() => api.data(table, { limit: PAGE, offset, experimentId: exp }), [table, offset, exp]);
  useEffect(() => { setOffset(0); }, [table, exp]);

  const cell = (v) => (v == null ? <span className="muted">null</span> : typeof v === 'boolean' ? String(v) : String(v).replace('T', ' '));
  return (
    <>
      <div className="page-head">
        <div>
          <h1>Data explorer</h1>
          <p className="sub">Browse every table in primary-key order. IDs join exactly across tables.</p>
        </div>
        <div className="row">
          <select value={table} onChange={(e) => setTable(e.target.value)} aria-label="Table">{TABLES.map((t) => <option key={t}>{t}</option>)}</select>
          <select value={exp} onChange={(e) => setExp(e.target.value)} aria-label="Experiment">
            <option value="">All experiments</option>
            {(exps || []).map((e) => <option key={e.experiment_id} value={e.experiment_id}>{e.experiment_id} · {e.name}</option>)}
          </select>
          <button onClick={reload}>Refresh</button>
        </div>
      </div>
      {table === 'ground_truth' && <div className="alert warning">Ground truth is shown for inspection only. It is never joined into the analysis inputs.</div>}
      <ErrorBox error={error} />
      {!data ? <Loading /> : (
        <div className="card">
          <div className="row" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
            <span className="text-2">{fmtInt(data.total)} rows · showing {data.total ? offset + 1 : 0}–{Math.min(offset + PAGE, data.total)}</span>
            <div className="row">
              <button disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>Previous</button>
              <button disabled={offset + PAGE >= data.total} onClick={() => setOffset(offset + PAGE)}>Next</button>
            </div>
          </div>
          <div className="table-wrap" style={{ maxHeight: 640 }}>
            <table>
              <thead><tr>{data.columns.map((c) => <th key={c}>{c}</th>)}</tr></thead>
              <tbody>{data.rows.map((r, i) => (
                <tr key={i}>{data.columns.map((c) => <td key={c} className="small" style={{ whiteSpace: 'nowrap' }}>{cell(r[c])}</td>)}</tr>
              ))}</tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}
