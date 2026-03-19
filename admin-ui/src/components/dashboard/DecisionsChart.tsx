import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer, Legend,
} from 'recharts'

// Données simulées basées sur les compteurs cumulés.
// En production, un endpoint /admin/metrics/timeseries fournirait
// les données temporelles réelles.
function buildChartData(allowTotal: number, denyTotal: number) {
  const now = Date.now()
  const points = 12
  return Array.from({ length: points }, (_, i) => {
    const t = new Date(now - (points - 1 - i) * 5 * 60 * 1000)
    const label = t.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
    const factor = (i + 1) / points
    return {
      time: label,
      allow: Math.round((allowTotal * factor) / points + Math.random() * 5),
      deny:  Math.round((denyTotal * factor) / points + Math.random() * 2),
    }
  })
}

interface Props {
  allowTotal?: number
  denyTotal?: number
}

export function DecisionsChart({ allowTotal = 0, denyTotal = 0 }: Props) {
  const data = buildChartData(allowTotal, denyTotal)

  return (
    <ResponsiveContainer width="100%" height={200}>
      <AreaChart data={data} margin={{ top: 5, right: 10, left: -10, bottom: 0 }}>
        <defs>
          <linearGradient id="gradAllow" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%"  stopColor="#22c55e" stopOpacity={0.3} />
            <stop offset="95%" stopColor="#22c55e" stopOpacity={0} />
          </linearGradient>
          <linearGradient id="gradDeny" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%"  stopColor="#ef4444" stopOpacity={0.3} />
            <stop offset="95%" stopColor="#ef4444" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
        <XAxis dataKey="time" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} tickLine={false} />
        <YAxis tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} tickLine={false} axisLine={false} />
        <Tooltip
          contentStyle={{
            backgroundColor: 'hsl(var(--card))',
            border: '1px solid hsl(var(--border))',
            borderRadius: '8px',
            fontSize: 12,
          }}
        />
        <Legend wrapperStyle={{ fontSize: 12 }} />
        <Area type="monotone" dataKey="allow" name="Allow" stroke="#22c55e" fill="url(#gradAllow)" strokeWidth={2} />
        <Area type="monotone" dataKey="deny"  name="Deny"  stroke="#ef4444" fill="url(#gradDeny)"  strokeWidth={2} />
      </AreaChart>
    </ResponsiveContainer>
  )
}
