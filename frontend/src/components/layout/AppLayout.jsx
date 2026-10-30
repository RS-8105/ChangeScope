import { Suspense } from 'react'
import { Outlet } from 'react-router-dom'
import AppSidebar from './AppSidebar.jsx'
import AppHeader from './AppHeader.jsx'

function PageLoadingFallback() {
  return (
    <div className="flex flex-col items-center justify-center min-h-[380px] w-full text-center animate-in fade-in duration-200">
      <div className="w-8 h-8 border-2 border-[#A78BFA] border-t-transparent rounded-full animate-spin mb-3" />
      <span className="text-xs text-[#858585] font-mono tracking-wide">
        Loading ChangeScope view...
      </span>
    </div>
  )
}

export default function AppLayout() {
  return (
    <div className="flex h-screen w-screen overflow-hidden bg-[#171717] text-[#D4D4D4] font-sans antialiased">
      {/* Fixed Left Navigation Rail */}
      <AppSidebar />

      {/* Main Working Space */}
      <div className="flex-1 flex flex-col h-full min-w-0 overflow-hidden">
        <AppHeader />
        <main className="flex-1 overflow-y-auto p-6">
          <div className="max-w-[1440px] mx-auto w-full">
            <Suspense fallback={<PageLoadingFallback />}>
              <Outlet />
            </Suspense>
          </div>
        </main>
      </div>
    </div>
  )
}
