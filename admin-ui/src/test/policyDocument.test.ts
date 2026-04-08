import { describe, expect, it } from 'vitest'
import { normalizePolicyForSave } from '@/lib/policyDocument'

describe('normalizePolicyForSave', () => {
  it('unwraps global policies into the backend save shape', () => {
    const document = {
      resourceClass: 'Facture',
      global: {
        strategy: 'deny-unless-permit',
        rules: [
          {
            name: 'R3',
            operation: 'lire',
            priority: 3,
            effect: 'allow',
            conditions: [],
          },
        ],
      },
    }

    expect(normalizePolicyForSave(document, 'Facture')).toEqual({
      resourceClass: 'Facture',
      strategy: 'deny-unless-permit',
      rules: [
        {
          name: 'R3',
          operation: 'lire',
          priority: 3,
          effect: 'allow',
          conditions: [],
        },
      ],
    })
  })

  it('keeps already-flat policies unchanged', () => {
    const document = {
      resourceClass: 'Facture',
      strategy: 'deny-unless-permit',
      rules: [],
    }

    expect(normalizePolicyForSave(document, 'Facture')).toEqual(document)
  })
})
