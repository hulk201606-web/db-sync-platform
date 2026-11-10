import { useEffect } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { Layout } from './components/Layout';
import { UNAUTHORIZED_EVENT } from './api/client';
import { useSession } from './api/auth';
import { Login } from './pages/Login';
import { ChangePassword } from './pages/ChangePassword';
import { Monitor } from './pages/Monitor';
import { Datasources } from './pages/Datasources';
import { Tasks } from './pages/Tasks';
import { TaskNew } from './pages/TaskNew';
import { TaskDetail } from './pages/TaskDetail';
import { Compare } from './pages/Compare';
import { Cutover } from './pages/Cutover';
import { System } from './pages/System';

/** 401 / E8004 统一跳登录 */
function AuthWatcher() {
  const navigate = useNavigate();
  useEffect(() => {
    function onUnauthorized(): void {
      navigate('/login', { replace: true });
    }
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, [navigate]);
  return null;
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const session = useSession();
  const location = useLocation();
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (session.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthWatcher />
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/change-password" element={<ChangePassword />} />
        <Route
          element={
            <RequireAuth>
              <Layout />
            </RequireAuth>
          }
        >
          <Route path="/" element={<Navigate to="/monitor" replace />} />
          <Route path="/monitor" element={<Monitor />} />
          <Route path="/datasources" element={<Datasources />} />
          <Route path="/tasks" element={<Tasks />} />
          <Route path="/tasks/new" element={<TaskNew />} />
          <Route path="/tasks/:id" element={<TaskDetail />} />
          <Route path="/compare/:jobId" element={<Compare />} />
          <Route path="/cutover/:id" element={<Cutover />} />
          <Route path="/system" element={<System />} />
          <Route path="*" element={<Navigate to="/monitor" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
