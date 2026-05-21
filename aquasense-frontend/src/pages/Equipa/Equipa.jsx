import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import api from '../../services/api';
import { useRole } from '../../hooks/useRole';
import { useLanguage } from '../../context/LanguageContext';
import s from './Equipa.module.css';

const ROLES = ['ADMIN', 'OPERADOR', 'MANTENIMIENTO', 'VISUALIZADOR'];

export default function Equipa() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { t } = useLanguage();
  const { isAdmin, loading: roleLoading } = useRole(id);
  const [members, setMembers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [addEmail, setAddEmail] = useState('');
  const [addRol, setAddRol] = useState('OPERADOR');
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState(null);

  const load = useCallback(() => {
    api.get(`/api/proyectos/${id}/roles`)
      .then(r => setMembers(r.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => {
    if (!roleLoading && !isAdmin) navigate(`/proyectos/${id}`);
  }, [roleLoading, isAdmin, id, navigate]);

  useEffect(() => { load(); }, [load]);

  const handleAdd = async () => {
    if (!addEmail.trim()) return;
    setAdding(true); setError(null);
    try {
      await api.post(`/api/proyectos/${id}/roles`, { email: addEmail.trim(), rol: addRol });
      setAddEmail('');
      load();
    } catch (e) {
      setError(e?.response?.data?.message ?? t('error_add_member_default'));
    } finally {
      setAdding(false);
    }
  };

  const handleChangeRol = async (member, newRol) => {
    setError(null);
    try {
      await api.post(`/api/proyectos/${id}/roles`, { email: member.email, rol: newRol });
      load();
    } catch (e) {
      setError(e?.response?.data?.message ?? t('error_add_member_default'));
    }
  };

  const handleRemove = async (usuarioId) => {
    setError(null);
    try {
      await api.delete(`/api/proyectos/${id}/roles/${usuarioId}`);
      load();
    } catch (e) {
      setError(e?.response?.data?.message ?? t('error_add_member_default'));
    }
  };

  if (roleLoading || loading) return null;

  return (
    <div className={s.root}>
      <header className={s.header}>
        <div className={s.headerLeft}>
          <button className={s.back} onClick={() => navigate(`/proyectos/${id}`)}>{t('back_to_dashboard')}</button>
          <div className={s.sep} />
          <span className={s.title}>{t('equipa_title')}</span>
        </div>
      </header>
      <div className={s.body}>
        <span className={s.sectionTitle}>{t('members_heading')}</span>
        <table className={s.table}>
          <thead>
            <tr>
              <th>Email</th>
              <th>{t('col_name')}</th>
              <th>{t('col_role')}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {members.length === 0 ? (
              <tr><td colSpan={4} style={{ padding: '20px', textAlign: 'center', color: 'var(--text3)', fontFamily: 'var(--mono)', fontSize: '11px' }}>
                {t('only_owner_msg')}
              </td></tr>
            ) : members.map(m => (
              <tr key={m.usuarioId}>
                <td>{m.email}</td>
                <td>{m.nombre ?? '—'}</td>
                <td>
                  <select className={s.rolSelect} value={m.rol}
                    onChange={e => handleChangeRol(m, e.target.value)}>
                    {ROLES.map(r => <option key={r} value={r}>{t('role_' + r)}</option>)}
                  </select>
                </td>
                <td>
                  <button className={s.removeBtn} onClick={() => handleRemove(m.usuarioId)}>
                    {t('remove_btn')}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        <span className={s.sectionTitle}>{t('add_member_heading')}</span>
        <div className={s.addForm}>
          <input className={s.addInput} type="email" placeholder="email@empresa.com"
            value={addEmail} onChange={e => setAddEmail(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && handleAdd()} />
          <select className={s.addSelect} value={addRol} onChange={e => setAddRol(e.target.value)}>
            {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
          </select>
          <button className={s.addBtn} onClick={handleAdd} disabled={adding || !addEmail.trim()}>
            {adding ? '...' : t('add_member_btn')}
          </button>
        </div>
        {error && <p className={s.error}>{error}</p>}
      </div>
    </div>
  );
}
