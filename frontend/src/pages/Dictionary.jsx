import { api } from '../api.js';
import { ErrorBox, Loading, useLoad } from '../components/ui.jsx';

export default function Dictionary() {
  const [data, error] = useLoad(() => api.dictionary());
  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  return (
    <>
      <div className="page-head">
        <div>
          <h1>Data dictionary</h1>
          <p className="sub">Generated from the backend schema registry. The same definitions create the MySQL DDL and drive validation.</p>
        </div>
      </div>
      <div className="card">
        <h2>Entities & relationships</h2>
        <table>
          <thead><tr><th>Table</th><th>Kind</th><th>Primary key</th><th>Cardinality</th></tr></thead>
          <tbody>{data.map((t) => (
            <tr key={t.table}><td className="mono"><a href={`#${t.table}`}>{t.table}</a></td><td className="small">{t.kind}</td><td className="mono small">{t.primary_key}</td><td className="small">{t.cardinality}</td></tr>
          ))}</tbody>
        </table>
      </div>
      {data.map((t) => (
        <div className="card" key={t.table} id={t.table}>
          <h2 className="mono">{t.table}</h2>
          <p className="text-2">{t.description}</p>
          <p className="small"><strong>Lifecycle:</strong> {t.lifecycle}{t.composite_unique.length > 0 && <> · <strong>Composite unique:</strong> {t.composite_unique.map((u) => `(${u})`).join(', ')}</>}</p>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Column</th><th>Type</th><th>Req.</th><th>Allowed values</th><th>Unit</th><th>Unique</th><th>Validation rule</th><th>FK</th><th>Description</th></tr></thead>
              <tbody>{t.columns.map((c) => (
                <tr key={c.name}>
                  <td className="mono">{c.name}</td><td className="mono small">{c.type}</td><td>{c.required ? 'yes' : 'no'}</td>
                  <td className="small">{c.allowed_values.join(', ')}</td><td className="small">{c.unit}</td><td>{c.unique ? 'yes' : ''}</td>
                  <td className="small">{c.pattern && <code>{c.pattern}</code>} {c.validation_rule}</td>
                  <td className="mono small">{c.foreign_key}</td><td className="small">{c.description}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        </div>
      ))}
    </>
  );
}
