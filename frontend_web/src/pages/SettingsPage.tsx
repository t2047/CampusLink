import NotificationsNoneOutlinedIcon from '@mui/icons-material/NotificationsNoneOutlined'
import PaletteOutlinedIcon from '@mui/icons-material/PaletteOutlined'
import TuneOutlinedIcon from '@mui/icons-material/TuneOutlined'

export function SettingsPage() {
  return (
    <div className="mx-auto max-w-4xl p-6 lg:p-10">
      <div className="mb-8">
        <p className="text-sm font-semibold uppercase tracking-wider text-blue-600">Workspace</p>
        <h1 className="mt-2 text-3xl font-bold tracking-tight text-slate-900 dark:text-white">Settings</h1>
        <p className="mt-2 text-slate-500 dark:text-slate-400">Customize your CampusLink workspace.</p>
      </div>
      <div className="grid gap-4 md:grid-cols-2">
        {[
          { title: 'Appearance', description: 'Manage theme and visual preferences.', icon: <PaletteOutlinedIcon /> },
          { title: 'Notifications', description: 'Choose how you receive workspace updates.', icon: <NotificationsNoneOutlinedIcon /> },
          { title: 'Agent preferences', description: 'Configure how the Campus AI Assistant responds.', icon: <TuneOutlinedIcon /> },
        ].map((item) => (
          <div key={item.title} className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-4 grid h-10 w-10 place-items-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-500/10 dark:text-blue-300">{item.icon}</div>
            <h2 className="font-semibold text-slate-800 dark:text-slate-100">{item.title}</h2>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{item.description}</p>
            <span className="mt-4 inline-flex rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-500 dark:bg-slate-800 dark:text-slate-400">Coming soon</span>
          </div>
        ))}
      </div>
    </div>
  )
}
