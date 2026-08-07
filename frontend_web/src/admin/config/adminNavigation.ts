import BusinessOutlinedIcon from '@mui/icons-material/BusinessOutlined'
import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined'
import FindInPageOutlinedIcon from '@mui/icons-material/FindInPageOutlined'
import PeopleOutlineIcon from '@mui/icons-material/PeopleOutline'

export const adminNavigation = [
  {
    label: 'Overview',
    path: '/admin/dashboard',
    icon: DashboardOutlinedIcon,
  },
  {
    label: 'Lost & Found',
    path: '/admin/lost-found',
    icon: FindInPageOutlinedIcon,
  },
  {
    label: 'Facilities',
    path: '/admin/facilities',
    icon: BusinessOutlinedIcon,
  },
  {
    label: 'User Management',
    path: '/admin/users',
    icon: PeopleOutlineIcon,
  },
] as const
