import { useEffect, useState } from 'react';
import { NavLink, Route, Routes } from 'react-router-dom';
import Overview from './pages/Overview.jsx';
import Experiments from './pages/Experiments.jsx';
import ExperimentDetail from './pages/ExperimentDetail.jsx';
import DesignExperiment from './pages/DesignExperiment.jsx';
import DataQuality from './pages/DataQuality.jsx';
import DataExplorer from './pages/DataExplorer.jsx';
import Dictionary from './pages/Dictionary.jsx';
import DataOps from './pages/DataOps.jsx';

const NAV = [
  ['/', 'Overview'],
  ['/experiments', 'Experiments'],
  ['/design', 'Design experiment'],
  ['/quality', 'Data quality & ground truth'],
  ['/explorer', 'Data explorer'],
  ['/dictionary', 'Data dictionary'],
  ['/ops', 'Generator & exports'],
];

export default function App() {
  const [theme, setTheme] = useState(() => {
    try { return localStorage.getItem('theme') || ''; } catch { return ''; }
  });
  useEffect(() => {
    if (theme) document.documentElement.setAttribute('data-theme', theme);
    else document.documentElement.removeAttribute('data-theme');
    try { localStorage.setItem('theme', theme); } catch { /* ignore */ }
  }, [theme]);

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="brand">Experiment Platform</div>
        <div className="brand-sub">Design · Randomize · Guard · Estimate</div>
        <nav className="nav">
          {NAV.map(([to, label]) => (
            <NavLink key={to} to={to} end={to === '/'}>{label}</NavLink>
          ))}
        </nav>
        <button className="theme-btn small" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>
          {theme === 'dark' ? 'Light theme' : 'Dark theme'}
        </button>
      </aside>
      <main className="main">
        <Routes>
          <Route path="/" element={<Overview />} />
          <Route path="/experiments" element={<Experiments />} />
          <Route path="/experiments/:id" element={<ExperimentDetail />} />
          <Route path="/design" element={<DesignExperiment />} />
          <Route path="/quality" element={<DataQuality />} />
          <Route path="/explorer" element={<DataExplorer />} />
          <Route path="/dictionary" element={<Dictionary />} />
          <Route path="/ops" element={<DataOps />} />
        </Routes>
      </main>
    </div>
  );
}
