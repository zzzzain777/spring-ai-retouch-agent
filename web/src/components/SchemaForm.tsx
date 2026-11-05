import type { JsonSchema } from '../types'
import { ENUM_LABELS, PARAM_LABELS } from '../labels'

export type Params = Record<string, unknown>

export interface LayerOption {
  id: string
  name: string
}

/**
 * 按工具的 JSON Schema（GET /api/tools 的 parameters，与给规划模型的是同一份）生成表单。
 * 前端不再维护一套参数规则：类型、范围、必填都来自 Schema，最终校验仍在 Java。
 */
export function SchemaForm({
  schema,
  value,
  onChange,
  layers,
  omit = [],
}: {
  schema: JsonSchema
  value: Params
  onChange: (next: Params) => void
  layers?: LayerOption[]
  omit?: string[]
}) {
  const properties = Object.entries(schema.properties ?? {}).filter(([key]) => !omit.includes(key))
  if (properties.length === 0) return <p className="hint">这个工具没有需要填写的参数。</p>
  const required = new Set(schema.required ?? [])
  const set = (key: string, next: unknown) => {
    const copy = { ...value }
    if (next === undefined || next === '') delete copy[key]
    else copy[key] = next
    onChange(copy)
  }
  return (
    <div className="schema-form">
      {properties.map(([key, field]) => (
        <Field
          key={key}
          name={key}
          field={field}
          required={required.has(key)}
          value={value[key]}
          onChange={(next) => set(key, next)}
          layers={layers}
        />
      ))}
    </div>
  )
}

function Field({
  name,
  field,
  required,
  value,
  onChange,
  layers,
}: {
  name: string
  field: JsonSchema
  required: boolean
  value: unknown
  onChange: (next: unknown) => void
  layers?: LayerOption[]
}) {
  const label = (
    <span className="field-label">
      {PARAM_LABELS[name] ?? name}
      {required && <em>*</em>}
    </span>
  )
  const hint = field.description && <small className="hint">{field.description}</small>

  if (name === 'layer_id' && layers) {
    return (
      <label className="field">
        {label}
        <select name={name} value={(value as string) ?? ''} onChange={(e) => onChange(e.target.value || undefined)}>
          <option value="">自动（选区所在或默认图层）</option>
          {layers.map((layer) => (
            <option key={layer.id} value={layer.id}>
              {layer.name}（{layer.id}）
            </option>
          ))}
        </select>
      </label>
    )
  }

  if (field.type === 'string' && field.enum) {
    return (
      <label className="field">
        {label}
        <select name={name} value={(value as string) ?? ''} onChange={(e) => onChange(e.target.value || undefined)}>
          <option value="">{required ? '请选择' : '默认'}</option>
          {field.enum.map((option) => (
            <option key={option} value={option}>
              {ENUM_LABELS[option] ?? option}
            </option>
          ))}
        </select>
        {hint}
      </label>
    )
  }

  if (field.type === 'array' && field.items?.enum) {
    const selected = Array.isArray(value) ? (value as string[]) : []
    const toggle = (option: string) => {
      const next = selected.includes(option) ? selected.filter((item) => item !== option) : [...selected, option]
      onChange(next.length ? next : undefined)
    }
    return (
      <fieldset className="field">
        {label}
        <div className="choices">
          {field.items.enum.map((option) => (
            <label key={option} className="choice">
              <input type="checkbox" name={name} value={option} checked={selected.includes(option)} onChange={() => toggle(option)} />
              {ENUM_LABELS[option] ?? option}
            </label>
          ))}
        </div>
        {hint}
      </fieldset>
    )
  }

  if (field.type === 'object' && field.properties) {
    const current = (value as Record<string, unknown>) ?? {}
    const keys = Object.keys(field.properties)
    const setPart = (key: string, raw: string) => {
      const next = { ...current }
      if (raw === '') delete next[key]
      else next[key] = Number(raw)
      onChange(keys.every((k) => typeof next[k] === 'number') ? next : Object.keys(next).length ? next : undefined)
    }
    return (
      <fieldset className="field">
        {label}
        <div className="inline">
          {keys.map((key) => (
            <label key={key} className="mini">
              {PARAM_LABELS[key] ?? key}
              <input
                type="number"
                step="0.05"
                min={field.properties![key].minimum ?? 0}
                max={field.properties![key].maximum ?? 1}
                value={(current[key] as number | undefined) ?? ''}
                onChange={(e) => setPart(key, e.target.value)}
              />
            </label>
          ))}
        </div>
      </fieldset>
    )
  }

  if (field.type === 'boolean') {
    return (
      <label className="field check">
        <input type="checkbox" name={name} checked={value === true} onChange={(e) => onChange(e.target.checked)} />
        {label}
        {hint}
      </label>
    )
  }

  if (field.type === 'number' || field.type === 'integer') {
    const min = field.minimum ?? field.exclusiveMinimum
    const max = field.maximum
    const slider = field.type === 'number' && min !== undefined && max !== undefined && max - min <= 2
    if (slider) {
      return (
        <label className="field">
          <span className="field-row">
            {label}
            <span className="value">{value === undefined ? '未设置' : Number(value).toFixed(2)}</span>
            {value !== undefined && (
              <button type="button" className="link" onClick={() => onChange(undefined)}>
                清除
              </button>
            )}
          </span>
          <input
            type="range"
            name={name}
            min={min}
            max={max}
            step={0.05}
            value={(value as number | undefined) ?? (min + max) / 2}
            onChange={(e) => onChange(Number(e.target.value))}
          />
          {hint}
        </label>
      )
    }
    return (
      <label className="field">
        {label}
        <input
          type="number"
          name={name}
          min={min}
          max={max}
          step={field.type === 'integer' ? 1 : 'any'}
          value={(value as number | undefined) ?? ''}
          onChange={(e) => onChange(e.target.value === '' ? undefined : Number(e.target.value))}
        />
        {hint}
      </label>
    )
  }

  const long = (field.maxLength ?? 0) >= 200
  return (
    <label className="field">
      {label}
      {long ? (
        <textarea name={name} rows={3} maxLength={field.maxLength} value={(value as string) ?? ''} onChange={(e) => onChange(e.target.value)} />
      ) : (
        <input name={name} maxLength={field.maxLength} value={(value as string) ?? ''} onChange={(e) => onChange(e.target.value)} />
      )}
      {hint}
    </label>
  )
}
