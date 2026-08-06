import CalendarTodayIcon from '@mui/icons-material/CalendarToday'
import LocationOnIcon from '@mui/icons-material/LocationOn'
import { Box, Card, CardActionArea, CardContent, CardMedia, Chip, Stack, Typography } from '@mui/material'
import { useNavigate } from 'react-router-dom'
import { categoryLabels, reportTypeLabels } from '../labels'
import type { LostFoundReport } from '../types'
import { StatusChip } from './StatusChip'

export function ReportCard({ report }: { report: LostFoundReport }) {
  const navigate = useNavigate()
  return (
    <Card sx={{ height: '100%' }}>
      <CardActionArea onClick={() => navigate(`/lost-found/${report.id}`)} sx={{ height: '100%', alignItems: 'stretch' }}>
        {report.images[0] ? (
          <CardMedia component="img" height="180" image={report.images[0].url} alt={report.itemName} sx={{ objectFit: 'cover' }} />
        ) : (
          <Box sx={{ height: 180, bgcolor: 'grey.200', display: 'grid', placeItems: 'center' }}><Typography color="text.secondary">No image</Typography></Box>
        )}
        <CardContent>
          <Stack direction="row" spacing={1} sx={{ mb: 1 }}>
            <Chip size="small" label={reportTypeLabels[report.reportType]} color={report.reportType === 'FOUND' ? 'success' : 'warning'} />
            <StatusChip status={report.status} />
          </Stack>
          <Typography variant="h6" gutterBottom>{report.itemName}</Typography>
          <Typography color="text.secondary" variant="body2" gutterBottom>{categoryLabels[report.category]}</Typography>
          <Stack spacing={0.5} color="text.secondary">
            <Typography variant="body2"><LocationOnIcon fontSize="inherit" /> {report.location}</Typography>
            <Typography variant="body2"><CalendarTodayIcon fontSize="inherit" /> {report.eventDate}</Typography>
          </Stack>
        </CardContent>
      </CardActionArea>
    </Card>
  )
}
