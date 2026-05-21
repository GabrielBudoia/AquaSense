import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { ProjectProvider } from './context/ProjectContext';
import { LanguageProvider } from './context/LanguageContext';
import ErrorBoundary from './components/ErrorBoundary/ErrorBoundary';
import ProtectedRoute from './components/ProtectedRoute/ProtectedRoute';
import Login from './pages/Login/Login';
import Proyectos from './pages/Proyectos/Proyectos';
import Dashboard from './pages/Dashboard/Dashboard';
import Alertas from './pages/Alertas/Alertas';

const Historico      = lazy(() => import('./pages/Historico/Historico'));
const Equipa         = lazy(() => import('./pages/Equipa/Equipa'));
const Auditoria      = lazy(() => import('./pages/Auditoria/Auditoria'));
const Notificaciones = lazy(() => import('./pages/Notificaciones/Notificaciones'));
const Perfil         = lazy(() => import('./pages/Perfil/Perfil'));

export default function App() {
  return (
    <ErrorBoundary>
    <LanguageProvider>
    <AuthProvider>
      <ProjectProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/proyectos" element={<ProtectedRoute><Proyectos /></ProtectedRoute>} />
            <Route path="/proyectos/:id" element={<ProtectedRoute><Dashboard /></ProtectedRoute>} />
            <Route path="/proyectos/:id/alertas" element={<ProtectedRoute><Alertas /></ProtectedRoute>} />
            <Route path="/proyectos/:id/historico" element={
              <ProtectedRoute>
                <Suspense fallback={<p style={{ padding: '2rem', textAlign: 'center' }}>···</p>}>
                  <Historico />
                </Suspense>
              </ProtectedRoute>
            } />
            <Route path="/proyectos/:id/equipa" element={
              <ProtectedRoute>
                <Suspense fallback={<p style={{ padding: '2rem', textAlign: 'center' }}>···</p>}><Equipa /></Suspense>
              </ProtectedRoute>
            } />
            <Route path="/proyectos/:id/auditoria" element={
              <ProtectedRoute>
                <Suspense fallback={<p style={{ padding: '2rem', textAlign: 'center' }}>···</p>}><Auditoria /></Suspense>
              </ProtectedRoute>
            } />
            <Route path="/proyectos/:id/notificaciones" element={
              <ProtectedRoute>
                <Suspense fallback={<p style={{ padding: '2rem', textAlign: 'center' }}>···</p>}><Notificaciones /></Suspense>
              </ProtectedRoute>
            } />
            <Route path="/perfil" element={
              <ProtectedRoute>
                <Suspense fallback={<p style={{ padding: '2rem', textAlign: 'center' }}>···</p>}><Perfil /></Suspense>
              </ProtectedRoute>
            } />
            <Route path="*" element={<Navigate to="/login" replace />} />
          </Routes>
        </BrowserRouter>
      </ProjectProvider>
    </AuthProvider>
    </LanguageProvider>
    </ErrorBoundary>
  );
}
