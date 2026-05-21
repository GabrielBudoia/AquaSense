import { useState, useEffect } from 'react';
import api from '../services/api';

const CACHE_TTL_MS = 30_000;
const cache = {};

function getCached(projectId) {
  const entry = cache[projectId];
  if (!entry) return null;
  if (Date.now() - entry.ts > CACHE_TTL_MS) { delete cache[projectId]; return null; }
  return entry.rol;
}

export function clearRoleCache() {
  Object.keys(cache).forEach(k => delete cache[k]);
}

export function useRole(projectId) {
  const cached = getCached(projectId);
  const [rol, setRol] = useState(cached);
  const [loading, setLoading] = useState(!cached);

  useEffect(() => {
    if (!projectId) return;
    const hit = getCached(projectId);
    if (hit) { setRol(hit); setLoading(false); return; }
    api.get(`/api/proyectos/${projectId}/mirol`)
      .then(r => { cache[projectId] = { rol: r.data.rol, ts: Date.now() }; setRol(r.data.rol); })
      .catch(() => { setRol('VISUALIZADOR'); })
      .finally(() => setLoading(false));
  }, [projectId]);

  const isAdmin        = rol === 'ADMIN';
  const isOperador     = rol === 'OPERADOR' || rol === 'ADMIN';
  const isMantenimiento= rol === 'MANTENIMIENTO' || rol === 'ADMIN';
  const canEdit        = isAdmin;
  const canControl     = isOperador;

  return { rol, loading, isAdmin, isOperador, isMantenimiento, canEdit, canControl };
}
