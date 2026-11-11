import { useState } from 'react';
import { api } from '../api.js';
import { Badge, ErrorBox, Loading, fmtDate, fmtInt, useLoad } from '../components/ui.jsx';

export default function DataOps() {
  const [runs, rErr, reloadRuns] = useLoad(() => api.generationRuns());
  const [manifest, , reloadManifest] = useLoad(() => api.manifest());
  const [form, setForm] = useState({ seed: 20261022, users: 50000, experiments: 10 });
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState(null);
  const [result, setResult] = useState(null);
  const [repro, setRepro] = useState(null);

  const run = async (kind, fn) => {
    setBusy(kind); setErr(null);
    try {
      const r = await fn();
      if (kind === 'repro') setRepro(r); else setResult({ kind, r });
      await reloadRuns(); await reloadManifest();
    } catch (e) { setErr(e); } finally { setBusy(''); }
  };
  const body = { seed: Number(form.seed), users: Number(form.users), experiments: Number(form.experiments) };

  return (
    <>
      <div className="page-head">
        <div>
          <h1>Generator & exports</h1>
          <p className="sub">Deterministic synthetic data: same seed and scale produce identical checksums.</p>
        </div>
      </div>
      <ErrorBox error={err || rErr} />
      <div className="grid grid-2">
        <div className="card">
          <h2>Generate dataset</h2>
          <div className="form-grid">
            <label className="field">Seed<input type="number" value={form.seed} onChange={(e) => setForm({ ...form, seed: e.target.value })} /></label>
            <label className="field">Users<input type="number" value={form.users} onChange={(e) => setForm({ ...form, users: e.target.value })} /></label>
            <label className="field">Experiments<input type="number" value={form.experiments} onChange={(e) => setForm({ ...form, experiments: e.target.value })} /></label>
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            <button onClick={() => setForm({ seed: 20261022, users: 5000, experiments: 10 })}>Starter (5k)</button>
            <button onClick={() => setForm({ seed: 20261022, users: 50000, experiments: 10 })}>Full (50k)</button>
          </div>
          <div className="alert warning" style={{ marginTop: 12 }}>Regenerating replaces all dataset tables, including experiments designed in the UI. Generation, validation and decision history are kept.</div>
          <div className="row">
            <button className="primary" disabled={!!busy} onClick={() => run('generate', () => api.generate(body))}>{busy === 'generate' ? 'Generating… (~40 s)' : 'Generate + validate'}</button>
            <button disabled={!!busy} onClick={() => run('export', () => api.exportData())}>{busy === 'export' ? 'Exporting…' : 'Export CSV / JSON / SQL'}</button>
            <button disabled={!!busy} onClick={() => run('repro', () => api.repro(body))}>{busy === 'repro' ? 'Regenerating twice… (~90 s)' : 'Reproducibility check'}</button>
          </div>
          <p className="small muted">CLI: <code>mvn spring-boot:run -Dspring-boot.run.arguments="--app.seed={form.seed} --app.users={form.users} --app.experiments={form.experiments} --generate --export --exit"</code></p>
          {result && (
            <div className="alert good">
              {result.kind === 'generate' && <>Generated {result.r.gen_run_id} in {(result.r.duration_ms / 1000).toFixed(1)} s · validation {result.r.validation?.validation_run_id}: {result.r.validation?.passed} passed, {result.r.validation?.failed} failed, {result.r.validation?.warnings} warnings</>}
              {result.kind === 'export' && <>Exported {result.r.files} files to <code>{result.r.export_dir}</code></>}
            </div>
          )}
        </div>

        <div className="card">
          <h2>Reproducibility</h2>
          {!repro ? <p className="muted">Runs the full pipeline twice with the same seed and compares row counts and SHA-256 checksums per table.</p> : (
            <>
              <p><Badge value={repro.reproducible ? 'PASS' : 'FAIL'}>{repro.reproducible ? 'Reproducible' : 'Not reproducible'}</Badge> <span className="small muted">{repro.run1} vs {repro.run2} · report saved to <code>{repro.report_file}</code></span></p>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Table</th><th className="num">Rows #1</th><th className="num">Rows #2</th><th>SHA-256</th><th>Same</th></tr></thead>
                  <tbody>{repro.tables.map((t) => (
                    <tr key={t.table}><td className="mono small">{t.table}</td><td className="num">{fmtInt(t.rows_run1)}</td><td className="num">{fmtInt(t.rows_run2)}</td>
                      <td className="mono small">{t.sha256_run1.slice(0, 16)}…</td><td><Badge value={t.identical ? 'PASS' : 'FAIL'}>{t.identical ? 'yes' : 'no'}</Badge></td></tr>
                  ))}</tbody>
                </table>
              </div>
            </>
          )}
        </div>
      </div>

      <div className="card">
        <h2>Latest export manifest</h2>
        {!manifest ? <Loading /> : manifest.message ? <p className="muted">{manifest.message}</p> : (
          <>
            <dl className="kv">
              <dt>Folder</dt><dd className="mono small">{manifest.export_dir}</dd>
              <dt>Seed / users / experiments</dt><dd>{manifest.seed} / {fmtInt(manifest.users)} / {manifest.experiments}</dd>
              <dt>Exported</dt><dd>{fmtDate(manifest.exported_at)}</dd>
              <dt>Provenance</dt><dd className="small">{manifest.provenance?.description}</dd>
              <dt>Command</dt><dd className="mono small">{manifest.generator_command}</dd>
            </dl>
            <div className="table-wrap" style={{ marginTop: 12, maxHeight: 360 }}>
              <table>
                <thead><tr><th>File</th><th className="num">Bytes</th><th>SHA-256</th></tr></thead>
                <tbody>{manifest.files.map((f) => (
                  <tr key={f.path}><td className="mono small">{f.path}</td><td className="num">{fmtInt(f.bytes)}</td><td className="mono small">{f.sha256.slice(0, 24)}…</td></tr>
                ))}</tbody>
              </table>
            </div>
          </>
        )}
      </div>

      <div className="card">
        <h2>Generation history</h2>
        {!runs ? <Loading /> : (
          <table>
            <thead><tr><th>Run</th><th>Status</th><th className="num">Seed</th><th className="num">Users</th><th className="num">Experiments</th><th>Started</th><th className="num">Seconds</th><th>By</th></tr></thead>
            <tbody>{runs.map((r) => (
              <tr key={r.gen_run_id}><td className="mono small">{r.gen_run_id}</td><td><Badge value={r.status === 'SUCCEEDED' ? 'PASS' : r.status === 'FAILED' ? 'FAIL' : 'WARN'}>{r.status}</Badge></td>
                <td className="num">{r.seed}</td><td className="num">{fmtInt(r.users)}</td><td className="num">{r.experiments}</td><td className="small">{fmtDate(r.started_at)}</td>
                <td className="num">{r.duration_ms ? (r.duration_ms / 1000).toFixed(1) : '—'}</td><td className="small">{r.triggered_by}</td></tr>
            ))}</tbody>
          </table>
        )}
      </div>
    </>
  );
}
