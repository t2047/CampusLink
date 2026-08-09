import { Button, Card, CardContent, Chip, Stack, Typography } from '@mui/material'
import { Link as RouterLink } from 'react-router-dom'

interface AdminPlaceholderPageProps {
  title: string
  description: string
  status?: string
}

export function AdminPlaceholderPage({ title, description, status }: AdminPlaceholderPageProps) {
  return (
    <Card variant="outlined" sx={{ maxWidth: 720 }}>
      <CardContent sx={{ p: { xs: 3, md: 4 } }}>
        <Stack spacing={3} alignItems="flex-start">
          <Typography component="h1" variant="h4" fontWeight={700}>{title}</Typography>
          {status && <Chip label={status} size="small" />}
          <Typography color="text.secondary">{description}</Typography>
          <Button component={RouterLink} to="/admin/dashboard" variant="contained">
            Return to Overview
          </Button>
        </Stack>
      </CardContent>
    </Card>
  )
}
