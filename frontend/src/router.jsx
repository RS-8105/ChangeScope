/* eslint-disable react-refresh/only-export-components */
import { lazy } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import AppLayout from './components/layout/AppLayout.jsx'

const DashboardPage = lazy(() => import('./pages/DashboardPage.jsx'))
const PRAnalysisPage = lazy(() => import('./pages/PRAnalysisPage.jsx'))
const DependencyGraphPage = lazy(() => import('./pages/DependencyGraphPage.jsx'))
const AnalysisHistoryPage = lazy(() => import('./pages/AnalysisHistoryPage.jsx'))
const DataManagementPage = lazy(() => import('./pages/DataManagementPage.jsx'))

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      {
        index: true,
        element: <DashboardPage />,
      },
      {
        path: 'history',
        element: <AnalysisHistoryPage />,
      },
      {
        path: 'settings/data-management',
        element: <DataManagementPage />,
      },
      {
        path: 'repositories/:repoId/pulls/:prNumber',
        element: <PRAnalysisPage />,
      },
      {
        path: 'repositories/:repoId/pulls/:prNumber/graph',
        element: <DependencyGraphPage />,
      },
      {
        path: 'overview',
        element: <Navigate to="/" replace />,
      },
      {
        path: '*',
        element: <Navigate to="/" replace />,
      },
    ],
  },
])

export default router
