// Rotas do portal (Spec 46). Públicas: landing page ("/"), login, forgot-password,
// reset-password. Privadas (RequireAuth): dashboard, carteira, cobrança, vendas,
// destinos, saque, webhooks, credenciais — todas sobre APIs existentes (thin client).

import { Routes, Route, Navigate } from 'react-router-dom';
import { RequireAuth } from './auth';
import Layout from './components/Layout';
import LandingPage from './pages/LandingPage';
import LoginPage from './pages/LoginPage';
import RegisterPage from './pages/RegisterPage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import ResetPasswordPage from './pages/ResetPasswordPage';
import DashboardPage from './pages/DashboardPage';
import WalletPage from './pages/WalletPage';
import ChargePage from './pages/ChargePage';
import SalesPage from './pages/SalesPage';
import DestinationsPage from './pages/DestinationsPage';
import WithdrawPage from './pages/WithdrawPage';
import WebhooksPage from './pages/WebhooksPage';
import CredentialsPage from './pages/CredentialsPage';
import BotsPage from './pages/BotsPage';
import TelegramBotCreatorWizard from './pages/bots/TelegramBotCreatorWizard';
import DiscordBotCreatorWizard from './pages/bots/DiscordBotCreatorWizard';
import BroadcastsPage from './pages/broadcasts/BroadcastsPage';
import BroadcastEditorPage from './pages/broadcasts/BroadcastEditorPage';
import TrackingPage from './pages/tracking/TrackingPage';
import CrmPage from './pages/crm/CrmPage';
import SecurityPage from './pages/SecurityPage';
import GatewaysPage from './pages/GatewaysPage';
import CommunityPage from './pages/CommunityPage';

export default function App() {
  return (
    <Routes>
      {/* Públicas */}
      {/* Landing page (marketing) — raiz do portal. */}
      <Route path="/" element={<LandingPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />

      {/* Privadas — layout com navegação */}
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        {/* Após o login o usuário vai para /dashboard (LoginPage) — sem redirect
            de "/" aqui: a raiz é a landing page pública. */}
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/wallet" element={<WalletPage />} />
        <Route path="/charges" element={<ChargePage />} />
        <Route path="/sales" element={<SalesPage />} />
        <Route path="/destinations" element={<DestinationsPage />} />
        <Route path="/withdraw" element={<WithdrawPage />} />
        <Route path="/webhooks" element={<WebhooksPage />} />
        <Route path="/bots" element={<BotsPage />} />
        <Route path="/bots/creator/telegram" element={<TelegramBotCreatorWizard />} />
        <Route path="/bots/creator/discord" element={<DiscordBotCreatorWizard />} />
        <Route path="/broadcasts" element={<BroadcastsPage />} />
        <Route path="/broadcasts/new" element={<BroadcastEditorPage />} />
        <Route path="/broadcasts/:id/edit" element={<BroadcastEditorPage />} />
        <Route path="/tracking" element={<TrackingPage />} />
        <Route path="/crm" element={<CrmPage />} />
        <Route path="/credentials" element={<CredentialsPage />} />
        <Route path="/security" element={<SecurityPage />} />
        {/* Spec 108: visão de gateways do lojista (adquirentes + fallback + catálogo). */}
        <Route path="/gateways" element={<GatewaysPage />} />
        {/* Spec 119: Comunidade — avisos/novidades da plataforma (read-only). */}
        <Route path="/community" element={<CommunityPage />} />
      </Route>

      {/* Fallback */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
