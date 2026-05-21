import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Chart as ChartJS, CategoryScale, LinearScale,
  PointElement, LineElement, Tooltip, Legend, Filler,
} from 'chart.js';
import { Line } from 'react-chartjs-2';
import api from '../../services/api';
import { useLanguage } from '../../context/LanguageContext';
import s from './Historico.module.css';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend, Filler);

const CHART_OPTIONS = {
  responsive: true,
  maintainAspectRatio: false,
  animation: false,
  plugins: {
    legend: {
      labels: { color: '#8aaec8', font: { family: 'JetBrains Mono', size: 9 }, boxWidth: 10, padding: 12 }, /* era #607890 — 4.09:1→8.00:1 */
    },
    tooltip: {
      backgroundColor: '#0b1220',
      borderColor: '#1a2840',
      borderWidth: 1,
      titleColor: '#8aaec8', /* era #607890 */
      bodyColor: '#d0e8f8',
      titleFont: { family: 'JetBrains Mono', size: 9 },
      bodyFont: { family: 'JetBrains Mono', size: 10 },
    },
  },
  scales: {
    x: {
      ticks: { color: '#6a8aaa', font: { family: 'JetBrains Mono', size: 9 }, maxTicksLimit: 8 }, /* era #304060 — 1.81:1→5.20:1 */
      grid: { color: '#1a2840' },
    },
    y: {
      ticks: { color: '#6a8aaa', font: { family: 'JetBrains Mono', size: 9 } }, /* era #304060 */
      grid: { color: '#1a2840' },
    },
  },
};

function mkDataset(label, data, color) {
  return {
    label,
    data,
    borderColor: color,
    backgroundColor: `${color}18`,
    borderWidth: 1.5,
    pointRadius: 0,
    tension: 0.3,
    fill: true,
  };
}

export default function Historico() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { t } = useLanguage();
  const [hist, setHist] = useState(null);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    api.get(`/api/proyectos/${id}/historico`).then(r => setHist(r.data)).finally(() => setLoading(false));
  }, [id]);

  // Eixo X partilhado por todos os datasets, ordenado cronologicamente
  const allTimestamps = [...new Set(hist?.map(h => h.timestamp) ?? [])].sort();

  const labels = allTimestamps.map(ts => {
    const d = new Date(ts);
    return `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;
  });

  // Alinha os valores de cada componente ao eixo temporal global (null para timestamps sem leitura)
  const get = (comp, key) => {
    const byTs = new Map(
      (hist?.filter(h => h.componente === comp) ?? []).map(h => [h.timestamp, h.valores?.[key] ?? null])
    );
    return allTimestamps.map(ts => byTs.get(ts) ?? null);
  };

  const charts = [
    {
      title: t('chart_chlorine'),
      datasets: [
        mkDataset(t('dataset_disinfection'), get('desinfeccion', 'cloroResidual'), '#00c8e8'),
        mkDataset(t('dataset_reservoir'),    get('reservorio',   'cloroResidual'), '#00e87a'),
      ],
    },
    {
      title: t('chart_ph'),
      datasets: [
        mkDataset(t('dataset_disinfection'), get('desinfeccion', 'ph'),               '#f5a623'),
        mkDataset(t('dataset_coagulation'),  get('coagulacion',  'phPostCoagulacion'), '#ff3d5a'),
      ],
    },
    {
      title: t('chart_level'),
      datasets: [
        mkDataset(t('dataset_reservoir'), get('reservorio', 'nivel'), '#00e87a'),
      ],
    },
    {
      title: t('chart_flow'),
      datasets: [
        mkDataset(t('dataset_pump_intake'), get('bomba_captacao',    'caudal'), '#00c8e8'),
        mkDataset(t('dataset_pump_dist'),   get('bomba_distribucion','caudal'), '#c084fc'),
      ],
    },
  ];

  return (
    <div className={s.root}>
      <header className={s.header}>
        <div className={s.headerLeft}>
          <button className={s.back} onClick={() => navigate(`/proyectos/${id}`)}>{t('back_to_dashboard')}</button>
          <div className={s.sep} />
          <span className={s.title}>{t('history_title')}</span>
        </div>
        <button
          style={{ background: 'transparent', border: '1px solid var(--border2)', color: 'var(--text3)', fontFamily: 'var(--mono)', fontSize: '11px', padding: '5px 12px', borderRadius: '2px', cursor: exporting ? 'not-allowed' : 'pointer', opacity: exporting ? 0.5 : 1 }}
          disabled={exporting}
          onClick={async () => {
            setExporting(true);
            try {
              const res = await api.get(`/api/proyectos/${id}/historico/export`, { responseType: 'blob' });
              const url = URL.createObjectURL(res.data);
              const a = document.createElement('a');
              a.href = url; a.download = `historico_${id}.csv`; a.click();
              URL.revokeObjectURL(url);
            } catch {} finally { setExporting(false); }
          }}
        >{exporting ? t('exporting_csv_btn') : t('export_csv_btn')}</button>
      </header>
      <div className={s.body}>
        {loading ? (
          <p className={s.loading}>{t('loading')}</p>
        ) : !hist || hist.length === 0 ? (
          <p className={s.loading}>{t('no_history')}</p>
        ) : (
          <div className={s.grid}>
            {charts.map(c => (
              <div key={c.title} className={s.chartCard}>
                <p className={s.chartTitle}>{c.title}</p>
                <div className={s.chartWrap}>
                  <Line data={{ labels, datasets: c.datasets }} options={CHART_OPTIONS} />
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
