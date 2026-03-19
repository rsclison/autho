import { type LucideIcon } from 'lucide-react'
import { cn } from '@/lib/utils'

interface Props {
  title: string
  value: string | number
  sub?: string
  icon: LucideIcon
  trend?: 'up' | 'down' | 'neutral'
  color?: 'default' | 'green' | 'red' | 'yellow'
}

const colorMap = {
  default: 'text-primary bg-primary/10',
  green:   'text-green-600 bg-green-100 dark:text-green-400 dark:bg-green-900/30',
  red:     'text-red-600 bg-red-100 dark:text-red-400 dark:bg-red-900/30',
  yellow:  'text-yellow-600 bg-yellow-100 dark:text-yellow-400 dark:bg-yellow-900/30',
}

export function MetricCard({ title, value, sub, icon: Icon, color = 'default' }: Props) {
  return (
    <div className="bg-card border border-border rounded-xl p-5 flex items-start gap-4">
      <div className={cn('p-2.5 rounded-lg', colorMap[color])}>
        <Icon size={18} />
      </div>
      <div className="min-w-0">
        <p className="text-sm text-muted-foreground">{title}</p>
        <p className="text-2xl font-bold text-foreground leading-tight">{value}</p>
        {sub && <p className="text-xs text-muted-foreground mt-0.5">{sub}</p>}
      </div>
    </div>
  )
}
