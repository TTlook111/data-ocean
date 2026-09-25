import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

function readFrontend(relativePath: string) {
  return readFileSync(resolve(process.cwd(), relativePath), 'utf8')
}

describe('B6-2 formal query entry', () => {
  it('keeps /query as the only implementation and aliases the old S1 URL', () => {
    const router = readFrontend('src/router/index.ts')
    expect(router).toContain("path: '/query'")
    expect(router).toContain('component: QueryDatasourceView')
    expect(router).toContain("path: '/query/iam-s1'")
    expect(router).toContain("path: '/query', query: to.query")
    expect(router).not.toContain("path: 'access'")
    expect(router).not.toContain("path: 'access/approvals'")
  })

  it('uses S1 APIs throughout the mounted query implementation', () => {
    const officialFiles = [
      'src/views/query/QueryDatasourceView.vue',
      'src/views/query/QuerySidebar.vue',
      'src/views/query/QueryResult.vue',
      'src/views/query/QueryProgress.vue',
      'src/views/query/IamS1ResourceSelector.vue',
      'src/composables/useQuerySession.ts',
      'src/composables/useQuerySubmit.ts',
      'src/composables/useQueryExport.ts',
      'src/utils/queryResult.ts',
    ]
    const source = officialFiles.map(readFrontend).join('\n')
    expect(source).toContain("from '../../api/iamS1'")
    expect(source).not.toContain("from '../../api/query'")
    expect(source).not.toContain("from '../api/query'")
    expect(source).not.toContain('/api/query/')
  })

  it('does not leave the retired access routes in formal navigation', () => {
    const navigation = readFrontend('src/router/adminNavigation.ts')
    const router = readFrontend('src/router/index.ts')
    for (const source of [navigation, router]) {
      expect(source).not.toContain("path: 'access'")
      expect(source).not.toContain("path: 'access/approvals'")
      expect(source).not.toContain("'/admin/access'")
      expect(source).not.toContain("'/admin/access/approvals'")
    }
    expect(router).toContain("path: 'access/iam'")
    expect(router).toContain("path: 'access/iam-approvals'")
    expect(router).toContain("path: 'access/iam-organization'")
  })
})
