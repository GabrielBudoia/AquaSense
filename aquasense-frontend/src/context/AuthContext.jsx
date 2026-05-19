import { createContext, useContext, useState, useEffect } from 'react';
import api from '../services/api';
import { useLanguage } from './LanguageContext';
import { clearRoleCache } from '../hooks/useRole';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const { setLang } = useLanguage();
  const [user, setUser] = useState(() => {
    const u = localStorage.getItem('aquasense_user');
    return u ? JSON.parse(u) : null;
  });

  // Sincroniza a lingua com o backend no arranque da sessão guardada.
  // Evita que um localStorage stale (e.g. "pt" de sessão antiga) sobreponha
  // a lingua real da conta quando o utilizador já tem token válido.
  useEffect(() => {
    const token = localStorage.getItem('aquasense_token');
    const stored = localStorage.getItem('aquasense_user');
    if (!token || !stored) return;
    api.get('/api/usuarios/me')
      .then(res => {
        const parsed = JSON.parse(stored);
        const fresh = { ...parsed, nombre: res.data.nombre, language: res.data.language };
        localStorage.setItem('aquasense_user', JSON.stringify(fresh));
        setUser(fresh);
        if (res.data.language) setLang(res.data.language);
      })
      .catch(() => {});
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const login = async (email, password) => {
    const res = await api.post('/auth/login', { email, password });
    localStorage.setItem('aquasense_token', res.data.token);
    localStorage.setItem('aquasense_user', JSON.stringify(res.data.usuario));
    setUser(res.data.usuario);
    if (res.data.usuario?.language) setLang(res.data.usuario.language);
    return res.data;
  };

  const register = async (nombre, email, password, language = 'en') => {
    const res = await api.post('/auth/register', { email, password, nombre, language });
    localStorage.setItem('aquasense_token', res.data.token);
    localStorage.setItem('aquasense_user', JSON.stringify(res.data.usuario));
    setUser(res.data.usuario);
    if (res.data.usuario?.language) setLang(res.data.usuario.language);
    return res.data;
  };

  const logout = async () => {
    try {
      await api.post('/auth/logout');
    } catch (_) {
      // fallo silencioso — logout local continúa
    } finally {
      clearRoleCache();
      localStorage.removeItem('aquasense_token');
      localStorage.removeItem('aquasense_user');
      setUser(null);
    }
  };

  const updateProfile = async (nombre, language) => {
    const res = await api.put('/api/usuarios/me', { nombre, language });
    const updated = { ...user, nombre: res.data.nombre, language: res.data.language };
    localStorage.setItem('aquasense_user', JSON.stringify(updated));
    setUser(updated);
    if (language) setLang(language);
    return res.data;
  };

  const changePassword = async (currentPassword, newPassword) => {
    await api.put('/api/usuarios/me/password', { currentPassword, newPassword });
  };

  const changeEmail = async (newEmail, password) => {
    await api.put('/api/usuarios/me/email', { newEmail, password });
    await logout();
  };

  const deleteAccount = async (password) => {
    await api.delete('/api/usuarios/me', { data: { password } });
    clearRoleCache();
    localStorage.removeItem('aquasense_token');
    localStorage.removeItem('aquasense_user');
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, login, register, logout, updateProfile, changePassword, changeEmail, deleteAccount }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
