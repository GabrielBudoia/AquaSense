import { useState, useCallback, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import api from '../../services/api';
import { useLanguage } from '../../context/LanguageContext';
import s from './Auditoria.module.css';

const truncate = (str, n = 50) => !str ? '—' : str.length > n ? str.slice(0, n) + '…' : str;

export default function Auditoria() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { t, localeCode } = useLanguage();

  const fmt = ts => ts
    ? new Date(ts).toLocaleString(localeCode, { dateStyle: 'short', timeStyle: 'short' })
    : '—';

  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [total, setTotal] = useState(0);
  const [filters, setFilters] = useState({ accion: '', usuario: '', desde: '', hasta: '' });
  const [applied, setApplied] = useState(filters);

  const load = useCallback((pg, f) => {
    setLoading(true);
    const params = { page: pg, size: 50 };
    if (f.accion) params.accion = f.accion;
    if (f.usuario) params.usuario = f.usuario;
    if (f.desde) params.desde = f.desde;
    if (f.hasta) params.hasta = f.hasta;
    api.get(`/api/proyectos/${id}/auditoria`, { params })
      .then(r => {
        setRows(r.data.content ?? []);
        setTotalPages(r.data.totalPages ?? 1);
        setTotal(r.data.totalElements ?? 0);
      })
      .catch(() => setRows([]))
      .finally(() => setLoading(false));
  }, [id]);

  // Carrega a primeira página ao montar o componente
  useEffect(() => { load(0, filters); }, [load]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleSearch = () => {
    setApplied(filters);
    setPage(0);
    load(0, filters);
  };

  const goPage = (pg) => {
    setPage(pg);
    load(pg, applied);
  };

  return (
    <div className={s.root}>
      <header className={s.header}>
        <div className={s.headerLeft}>
          <button className={s.back} onClick={() => navigate(`/proyectos/${id}`)}>{t('back_to_dashboard')}</button>
          <div className={s.sep} />
          <span className={s.title}>{t('auditoria_title')}</span>
        </div>
      </header>

      <div className={s.filters}>
        <div className={s.field}>
          <span className={s.label}>{t('filter_action_label')}</span>
          <input className={s.input} placeholder="CONTROL_MANUAL..." value={filters.accion}
            onChange={e => setFilters(f => ({ ...f, accion: e.target.value }))} />
        </div>
        <div className={s.field}>
          <span className={s.label}>{t('filter_user_label')}</span>
          <input className={s.input} placeholder="email@..." value={filters.usuario}
            onChange={e => setFilters(f => ({ ...f, usuario: e.target.value }))} />
        </div>
        <div className={s.field}>
          <span className={s.label}>{t('filter_from_label')}</span>
          <input className={s.input} type="date" value={filters.desde}
            onChange={e => setFilters(f => ({ ...f, desde: e.target.value }))} />
        </div>
        <div className={s.field}>
          <span className={s.label}>{t('filter_to_label')}</span>
          <input className={s.input} type="date" value={filters.hasta}
            onChange={e => setFilters(f => ({ ...f, hasta: e.target.value }))} />
        </div>
        <button className={s.searchBtn} onClick={handleSearch}>{t('search_btn')}</button>
      </div>

      <div className={s.tableWrap}>
        {loading ? (
          <p className={s.loading}>{t('loading')}</p>
        ) : rows.length === 0 ? (
          <p className={s.empty}>{t('audit_empty_hint')}</p>
        ) : (
          <table className={s.table}>
            <thead>
              <tr>
                <th>{t('col_timestamp')}</th>
                <th>{t('col_user')}</th>
                <th>{t('col_action')}</th>
                <th>{t('col_entity')}</th>
                <th>{t('col_value_after')}</th>
                <th>IP</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.id}>
                  <td className={s.ts}>{fmt(r.timestamp)}</td>
                  <td>{r.usuario}</td>
                  <td className={s.accion}>{r.accion}</td>
                  <td>{r.entidade}</td>
                  <td className={s.val}>{truncate(r.valorDespues)}</td>
                  <td className={s.ip}>{r.ip ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {totalPages > 1 && (
        <div className={s.pagination}>
          <span className={s.pageInfo}>
            {total} {t('audit_events_label')} · {t('page_label')} {page + 1} {t('of_label')} {totalPages}
          </span>
          <button className={s.pageBtn} onClick={() => goPage(page - 1)} disabled={page === 0}>
            {t('prev_page')}
          </button>
          <button className={s.pageBtn} onClick={() => goPage(page + 1)} disabled={page >= totalPages - 1}>
            {t('next_page')}
          </button>
        </div>
      )}
    </div>
  );
}
