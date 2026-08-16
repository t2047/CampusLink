import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import {
  Accordion, AccordionDetails, AccordionSummary, Box, Button, Stack, ToggleButton,
  ToggleButtonGroup, Typography,
} from '@mui/material'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'

interface FaqItem {
  q: string
  a: string
}

const faqZh: FaqItem[] = [
  { q: '如何发布失物报告？', a: '点击顶部导航的 Report，或进入 Browse 页点击 “Report lost”。填写物品名称、分类、描述、地点与日期，可上传最多 5 张照片（JPEG/PNG/WebP，每张 ≤10MB）。提交后报告状态为 OPEN。' },
  { q: '如何发布拾物报告？', a: '点击顶部导航的 Report，或进入 Browse 页点击 “Report found”。填写拾获物品的信息与照片，提交后其他人可以提交认领申请。' },
  { q: '如何提交认领申请？', a: '在物品详情页点击 “Claim this item”，填写所有权证明（序列号、特征、票据等）。同一报告可提交多个申请，审核通过后其他待处理申请会被自动拒绝。' },
  { q: '为什么我不能认领自己发布的物品？', a: '为了防止自导自演，系统不允许对你自己发布的报告提交认领申请。' },
  { q: '认领申请提交后由谁审核？', a: '申请由管理员统一审核。审核结果（通过/拒绝）会在 My Claims 页面展示，并附带审核备注。' },
  { q: '报告状态 OPEN、CLAIMED、CLOSED 分别代表什么？', a: 'OPEN 表示仍在寻找失主/等待认领；CLAIMED 表示认领申请已获批准、物品已匹配；CLOSED 表示报告已被发布者关闭或物品已找到，不再接收新的认领申请。' },
  { q: '图片上传有什么限制？', a: '图片必须是 JPEG、PNG 或 WebP，每张 ≤10MB、单边 ≤8192 像素，最多 5 张。头像上传为 ≤2MB、单边 ≤512 像素。' },
  { q: '如何关闭或删除我发布的报告？', a: '在报告详情页，只有状态为 OPEN 的报告可以关闭或删除。关闭后状态变为 CLOSED；删除后报告与相关认领申请会被移除。' },
  { q: '如果发现虚假信息或敏感信息怎么办？', a: '请联系管理员举报。管理员核实后可下架报告；下架后该报告不再出现在公开搜索中。' },
  { q: '如何联系失主/拾主？', a: 'CampusLink 目前不直接展示联系方式。请提交认领申请，审核通过后由平台协助建立联系。' },
  { q: '我的报告被管理员下架意味着什么？', a: '说明报告存在虚假、敏感或违规信息，已被管理员下架。你可以在 “我的失物/我的拾物” 中看到该报告并带有下架标识，但它不会出现在公开搜索中。' },
]

const faqEn: FaqItem[] = [
  { q: 'How do I post a lost-item report?', a: 'Click Report in the top navigation, or choose “Report lost” on the Browse page. Enter the item name, category, description, location and date, and attach up to 5 photos (JPEG/PNG/WebP, ≤10MB each). The report starts with status OPEN.' },
  { q: 'How do I post a found-item report?', a: 'Click Report in the top navigation, or choose “Report found” on the Browse page. Enter the found item details and photos, then others can submit claims on it.' },
  { q: 'How do I submit a claim?', a: 'Open the item detail page and click “Claim this item”, then describe your proof of ownership (serial number, distinguishing features, receipt, etc.). Multiple claims are allowed; when one is approved the other pending claims are automatically rejected.' },
  { q: 'Why can’t I claim my own item?', a: 'To prevent self-dealing, the system does not allow claims on reports you posted yourself.' },
  { q: 'Who reviews my claim after submission?', a: 'Claims are reviewed by administrators. The decision (approved / rejected) appears on the My Claims page together with any decision note.' },
  { q: 'What do OPEN, CLAIMED and CLOSED statuses mean?', a: 'OPEN means the item is still being matched; CLAIMED means a claim was approved and the item is matched; CLOSED means the report was closed by its author or the item was recovered, and no new claims are accepted.' },
  { q: 'What are the image upload limits?', a: 'Images must be JPEG, PNG or WebP, at most 10MB each and 8192 pixels per side, with a maximum of 5 images. Avatar uploads are limited to 2MB and 512 pixels per side.' },
  { q: 'How do I close or delete a report I posted?', a: 'On the report detail page, only reports with status OPEN can be closed or deleted. Closing sets the status to CLOSED; deleting removes the report and its related claims.' },
  { q: 'What if I see false or sensitive information?', a: 'Contact an administrator to report it. After verification the report can be removed, after which it no longer appears in public search results.' },
  { q: 'How do I contact the owner/finder?', a: 'CampusLink does not expose contact details directly. Submit a claim and the platform will help establish contact once it is approved.' },
  { q: 'What does it mean if my report was removed by an administrator?', a: 'The report contained false, sensitive or policy-violating information and was removed. It still appears in My Lost/My Found with a removal badge, but not in public search results.' },
]

/** Lost & Found FAQ（个人中心需求 §6.3 / FR-7）：中英双语静态页，可切换。 */
export function LostFoundFaqPage() {
  const [lang, setLang] = useState<'zh' | 'en'>('zh')
  const items = lang === 'zh' ? faqZh : faqEn

  return (
    <Stack spacing={3}>
      <Box>
        <Button component={RouterLink} to="/lost-found/profile" startIcon={<ArrowBackIcon />} sx={{ mb: 1, textTransform: 'none' }}>Back to personal center</Button>
        <Typography variant="h4" fontWeight={700}>Lost &amp; Found FAQ</Typography>
        <Typography color="text.secondary">Usage instructions, claiming rules and common questions.</Typography>
      </Box>

      <Box>
        <ToggleButtonGroup
          exclusive
          value={lang}
          onChange={(_, next) => { if (next) setLang(next) }}
          aria-label="FAQ language"
        >
          <ToggleButton value="zh">中文</ToggleButton>
          <ToggleButton value="en">English</ToggleButton>
        </ToggleButtonGroup>
      </Box>

      {items.map((item, index) => (
        <Accordion key={lang + index} defaultExpanded={index === 0}>
          <AccordionSummary expandIcon={<ExpandMoreIcon />}>
            <Typography fontWeight={600}>{item.q}</Typography>
          </AccordionSummary>
          <AccordionDetails>
            <Typography color="text.secondary" sx={{ whiteSpace: 'pre-wrap' }}>{item.a}</Typography>
          </AccordionDetails>
        </Accordion>
      ))}
    </Stack>
  )
}
