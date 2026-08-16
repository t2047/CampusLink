import AppsOutlinedIcon from '@mui/icons-material/AppsOutlined'
import BuildOutlinedIcon from '@mui/icons-material/BuildOutlined'
import CalendarMonthOutlinedIcon from '@mui/icons-material/CalendarMonthOutlined'
import ChatBubbleOutlineOutlinedIcon from '@mui/icons-material/ChatBubbleOutlineOutlined'
import ChevronDownIcon from '@mui/icons-material/ExpandMore'
import EventAvailableOutlinedIcon from '@mui/icons-material/EventAvailableOutlined'
import HelpOutlineOutlinedIcon from '@mui/icons-material/HelpOutlineOutlined'
import LogoutOutlinedIcon from '@mui/icons-material/LogoutOutlined'
import MailOutlineIcon from '@mui/icons-material/MailOutline'
import MeetingRoomOutlinedIcon from '@mui/icons-material/MeetingRoomOutlined'
import PersonOutlineOutlinedIcon from '@mui/icons-material/PersonOutlineOutlined'
import SearchOutlinedIcon from '@mui/icons-material/SearchOutlined'
import KeyboardDoubleArrowLeftOutlinedIcon from '@mui/icons-material/KeyboardDoubleArrowLeftOutlined'
import KeyboardDoubleArrowRightOutlinedIcon from '@mui/icons-material/KeyboardDoubleArrowRightOutlined'
import { useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { displayName } from '../auth/displayName'
import ChatPage from '../pages/ChatPage'
import { UserAvatar } from './UserAvatar'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `group flex items-center gap-3 rounded-xl px-3 py-2 text-sm transition-colors ${
    isActive
      ? 'bg-blue-50 font-semibold text-blue-700 dark:bg-blue-500/10 dark:text-blue-300'
      : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white'
  }`

function SidebarLink({ to, label, icon }: { to: string; label: string; icon: React.ReactNode }) {
  return <NavLink end to={to} className={linkClass}>{icon}<span>{label}</span></NavLink>
}

export function WorkspaceShell() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, logout } = useAuth()
  const [lostFoundOpen, setLostFoundOpen] = useState(true)
  const [facilitiesOpen, setFacilitiesOpen] = useState(true)
  const [agentOpen, setAgentOpen] = useState(true)
  const [workspaceOpen, setWorkspaceOpen] = useState(true)
  const profileName = user ? displayName(user.nickname, user.email) : 'CampusLink user'
  const isAgent = location.pathname === '/chat'

  return (
    <div className="flex h-screen overflow-hidden bg-slate-100 text-slate-800 dark:bg-slate-950 dark:text-slate-100">
      {workspaceOpen ? (
      <aside className="relative flex w-72 shrink-0 flex-col border-r border-slate-200 bg-white px-3 py-4 dark:border-slate-800 dark:bg-slate-900">
        <button type="button" onClick={() => setWorkspaceOpen(false)} aria-label="Hide Workspace" title="Hide Workspace" className="absolute right-2 top-2 z-10 grid h-8 w-8 place-items-center rounded-lg text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800 dark:hover:text-slate-200">
          <KeyboardDoubleArrowLeftOutlinedIcon sx={{ fontSize: 18 }} />
        </button>
        <NavLink end to="/chat" aria-label="CampusLink" className="mb-5 flex items-center gap-3 rounded-xl px-2 py-1 hover:bg-slate-50 dark:hover:bg-slate-800">
          <div className="grid h-10 w-10 place-items-center rounded-xl bg-gradient-to-br from-blue-600 to-indigo-700 text-white shadow-sm"><AppsOutlinedIcon /></div>
          <div>
            <p className="font-bold tracking-tight text-slate-900 dark:text-white">CampusLink</p>
            <p className="text-xs text-slate-400">Campus workspace</p>
          </div>
        </NavLink>

        <nav className="min-h-0 flex-1 space-y-1 overflow-y-auto" aria-label="Workspace navigation">
          <p className="px-3 pb-2 pt-1 text-[11px] font-semibold uppercase tracking-wider text-slate-400">Workspace</p>
          <SidebarLink to="/chat" label="Agent" icon={<ChatBubbleOutlineOutlinedIcon sx={{ fontSize: 19 }} />} />
          <button type="button" onClick={() => setLostFoundOpen((value) => !value)} className="flex w-full items-center justify-between rounded-xl px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800">
            <span className="flex items-center gap-3"><SearchOutlinedIcon sx={{ fontSize: 19 }} />Lost &amp; Found</span>
            <ChevronDownIcon className={`transition-transform ${lostFoundOpen ? 'rotate-180' : ''}`} sx={{ fontSize: 18 }} />
          </button>
          {lostFoundOpen && <div className="ml-3 space-y-1 border-l border-slate-200 pl-3 dark:border-slate-700">
            <SidebarLink to="/lost-found" label="Search" icon={<SearchOutlinedIcon sx={{ fontSize: 17 }} />} />
            <SidebarLink to="/lost-found/new/lost" label="Report Lost" icon={<SearchOutlinedIcon sx={{ fontSize: 17 }} />} />
            <SidebarLink to="/lost-found/new/found" label="Report Found" icon={<SearchOutlinedIcon sx={{ fontSize: 17 }} />} />
            <SidebarLink to="/claims/mine" label="Claims" icon={<HelpOutlineOutlinedIcon sx={{ fontSize: 17 }} />} />
          </div>}
          <button type="button" onClick={() => setFacilitiesOpen((value) => !value)} className="flex w-full items-center justify-between rounded-xl px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-800">
            <span className="flex items-center gap-3"><MeetingRoomOutlinedIcon sx={{ fontSize: 19 }} />Facilities</span>
            <ChevronDownIcon className={`transition-transform ${facilitiesOpen ? 'rotate-180' : ''}`} sx={{ fontSize: 18 }} />
          </button>
          {facilitiesOpen && <div className="ml-3 space-y-1 border-l border-slate-200 pl-3 dark:border-slate-700">
            <SidebarLink to="/facilities" label="Search Spaces" icon={<MeetingRoomOutlinedIcon sx={{ fontSize: 17 }} />} />
            <SidebarLink to="/facilities/bookings" label="My Bookings" icon={<EventAvailableOutlinedIcon sx={{ fontSize: 17 }} />} />
            <SidebarLink to="/facilities/maintenance" label="Maintenance" icon={<BuildOutlinedIcon sx={{ fontSize: 17 }} />} />
          </div>}
          <SidebarLink to="/mail" label="Mail" icon={<MailOutlineIcon sx={{ fontSize: 19 }} />} />
          <SidebarLink to="/mail/calendar" label="Calendar" icon={<CalendarMonthOutlinedIcon sx={{ fontSize: 19 }} />} />
        </nav>

        <div className="mt-4 space-y-1 border-t border-slate-200 pt-3 dark:border-slate-800">
          <SidebarLink to="/lost-found/profile" label="Personal center" icon={<PersonOutlineOutlinedIcon sx={{ fontSize: 19 }} />} />
          <button type="button" onClick={() => { logout(); navigate('/login') }} className="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-sm text-slate-500 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white">
            <LogoutOutlinedIcon sx={{ fontSize: 19 }} /><span>Sign out</span>
          </button>
          <NavLink to="/lost-found/profile" className="mt-2 flex items-center gap-3 rounded-xl bg-slate-50 px-3 py-2 dark:bg-slate-800">
            <UserAvatar name={profileName} avatarUrl={user?.avatarUrl} size={30} />
            <span className="min-w-0"><span className="block truncate text-xs font-semibold text-slate-700 dark:text-slate-200">{profileName}</span><span className="block text-[11px] text-slate-400">View profile</span></span>
          </NavLink>
        </div>
      </aside>
      ) : (
        <button type="button" onClick={() => setWorkspaceOpen(true)} aria-label="Show Workspace" title="Show Workspace" className="fixed left-0 top-1/2 z-30 hidden -translate-y-1/2 rounded-r-xl border border-l-0 border-slate-200 bg-white px-2 py-4 text-slate-500 shadow-lg transition-colors hover:bg-blue-50 hover:text-blue-700 xl:grid dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700 dark:hover:text-blue-300">
          <KeyboardDoubleArrowRightOutlinedIcon sx={{ fontSize: 18 }} />
        </button>
      )}

      <main className={`min-w-0 flex-1 overflow-y-auto ${isAgent ? '' : 'px-6 py-6'}`}>
        <Outlet />
      </main>
      {!isAgent && (
        agentOpen ? (
          <aside className="relative hidden w-[360px] shrink-0 border-l border-slate-200 bg-white xl:flex dark:border-slate-800 dark:bg-slate-900">
            <button type="button" onClick={() => setAgentOpen(false)} aria-label="Hide Agent" title="Hide Agent" className="absolute right-2 top-2 z-10 grid h-8 w-8 place-items-center rounded-lg bg-white/90 text-slate-400 shadow-sm transition-colors hover:bg-slate-100 hover:text-slate-700 dark:bg-slate-800/90 dark:hover:bg-slate-700 dark:hover:text-slate-200">
              <KeyboardDoubleArrowRightOutlinedIcon sx={{ fontSize: 18 }} />
            </button>
            <ChatPage compact />
          </aside>
        ) : (
          <button type="button" onClick={() => setAgentOpen(true)} aria-label="Show Agent" title="Show Agent" className="fixed right-0 top-1/2 z-30 hidden -translate-y-1/2 rounded-l-xl border border-r-0 border-slate-200 bg-white px-2 py-4 text-slate-500 shadow-lg transition-colors hover:bg-blue-50 hover:text-blue-700 xl:grid dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700 dark:hover:text-blue-300">
            <KeyboardDoubleArrowLeftOutlinedIcon sx={{ fontSize: 18 }} />
          </button>
        )
      )}
    </div>
  )
}
