// Rotas do Master Console (Spec 71, fatia 1 + Spec 72, fatia 2). Pública:
// /console/login. Privadas (RequireAuth + Layout): dashboard, transações,
// lojistas, saques, financeiro, receivables, auditoria, atividades,
// reconciliação, lookup, MFA (71) + perfis master (72 — admin-only) — todas
// sobre APIs ROLE_MASTER existentes + os endpoints aditivos (thin client).

import { Routes, Route, Navigate } from 'react-router-dom';
import { RequireAuth } from './auth';
import Layout from './components/Layout';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import TransactionsPage from './pages/TransactionsPage';
import MerchantsPage from './pages/MerchantsPage';
import MerchantDetailPage from './pages/MerchantDetailPage';
import MerchantConfigPage from './pages/MerchantConfigPage';
import WithdrawalsPage from './pages/WithdrawalsPage';
import FinancialPage from './pages/FinancialPage';
import ReceivablesPage from './pages/ReceivablesPage';
import AuditPage from './pages/AuditPage';
import ActivityPage from './pages/ActivityPage';
import ReconciliationPage from './pages/ReconciliationPage';
import LookupPage from './pages/LookupPage';
import SecurityPage from './pages/SecurityPage';
import ProfilesPage from './pages/ProfilesPage';
import BotsPage from './pages/BotsPage';
import BroadcastsPage from './pages/BroadcastsPage';
import TracePage from './pages/TracePage';

export default function App() {
  return (
    <Routes>
      {/* Pública */}
      <Route path="/console/login" element={<LoginPage />} />

      {/* Privadas — layout com navegação */}
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route path="/console" element={<Navigate to="/console/dashboard" replace />} />
        <Route path="/console/dashboard" element={<DashboardPage />} />
        <Route path="/console/transactions" element={<TransactionsPage />} />
        <Route path="/console/merchants" element={<MerchantsPage />} />
        {/* Spec 100: config fina por lojista — rota ANTES de `:id/*` (ordem do
            React Router; `:id` casa um segmento, config não casaria). */}
        <Route path="/console/merchants/:id/config" element={<MerchantConfigPage />} />
        {/* :id/* — o detalhe tem sub-rotas (tabs) via <Routes> interno; em
            React Router v6 `:id` casa UM segmento (fees/ledger/... não casaria). */}
        <Route path="/console/merchants/:id/*" element={<MerchantDetailPage />} />
        <Route path="/console/withdrawals" element={<WithdrawalsPage />} />
        <Route path="/console/financial" element={<FinancialPage />} />
        <Route path="/console/receivables" element={<ReceivablesPage />} />
        <Route path="/console/audit" element={<AuditPage />} />
        <Route path="/console/activity" element={<ActivityPage />} />
        <Route path="/console/reconciliation" element={<ReconciliationPage />} />
        <Route path="/console/lookup" element={<LookupPage />} />
        <Route path="/console/bots" element={<BotsPage />} />
        <Route path="/console/broadcasts" element={<BroadcastsPage />} />
        {/* Spec 127: trace de transação (fases 3–4 da Spec 125). */}
        <Route path="/console/trace" element={<TracePage />} />
        <Route path="/console/security" element={<SecurityPage />} />
        <Route path="/console/profiles" element={<ProfilesPage />} />
      </Route>

      {/* Fallback */}
      <Route path="*" element={<Navigate to="/console" replace />} />
    </Routes>
  );
}
