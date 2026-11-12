/**
 * Spectral 自定义函数：校验每个 operation 的错误响应语义。
 *
 * 规则见 packages/contracts/.spectral.yaml 的 error-responses。检查项：
 *   1. 所有 4xx/5xx 响应必须 `$ref` 到共享 Error 响应组件（#/components/responses/*），
 *      且状态码与组件名一一对应，禁止内联定义错误体；
 *   2. 401 所有 operation 必备；
 *   3. 需要认证的 operation 必备 403（logout 仅 204/401，作为显式例外）；
 *   4. 带 requestBody 的 operation 必备 400（入参校验失败）；
 *   5. 路径含资源参数 `{...}` 的 operation 必备 404；集合查询/创建类不得声明 404；
 *   6. 409 只允许出现在状态变更操作（带 requestBody）上；GET/DELETE 等不得声明。
 *
 * 404/409 为条件化声明：只有单资源操作或业务状态冲突才出现，避免“每个接口都 404/409”的噪声。
 */

const REF_PREFIX = '#/components/responses/'
const STATUS_COMPONENT = {
  400: 'BadRequest',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'NotFound',
  409: 'Conflict',
  422: 'UnprocessableEntity',
}

module.exports = function errorResponses(targetVal, _opts, context) {
  const operation = targetVal || {}
  const responses = operation.responses || {}
  const document = (context && context.document && context.document.data) || {}
  const template = Array.isArray(context && context.path) ? context.path[1] : ''
  const method = Array.isArray(context && context.path) ? context.path[2] : ''
  const rawOperation =
    template && method && document.paths && document.paths[template]
      ? document.paths[template][method]
      : undefined
  const rawResponses = (rawOperation && rawOperation.responses) || {}
  const hasPathParam = typeof template === 'string' && /\{[^}]+\}/.test(template)
  const hasRequestBody = Boolean(operation.requestBody)
  const security = operation.security !== undefined ? operation.security : document.security
  const isUnauthenticated = !Array.isArray(security) || security.length === 0
  const requiresAuth = !isUnauthenticated && operation.operationId !== 'logout'

  const errors = []
  const push = (message, path) => errors.push({ message, path })

  for (const [status, response] of Object.entries(rawResponses)) {
    if (!/^[45]\d\d$/.test(status)) {
      continue
    }
    const ref = response && response.$ref
    if (typeof ref !== 'string' || !ref.startsWith(REF_PREFIX)) {
      push(
        `${status} 必须通过 $ref 引用共享错误响应组件（${REF_PREFIX}*），禁止内联定义错误响应体`,
        ['responses', status],
      )
      continue
    }
    const componentName = ref.slice(REF_PREFIX.length)
    const component =
      document.components && document.components.responses
        ? document.components.responses[componentName]
        : undefined
    if (!component) {
      push(`${status} 引用的组件 components.responses.${componentName} 不存在`, [
        'responses',
        status,
        '$ref',
      ])
      continue
    }
    const expected = STATUS_COMPONENT[status]
    if (expected && componentName !== expected) {
      push(`${status} 应引用 ${REF_PREFIX}${expected}，当前引用 ${REF_PREFIX}${componentName}`, [
        'responses',
        status,
        '$ref',
      ])
    }
  }

  if (!responses['401']) {
    push('每个 operation 必须声明 401（未认证/令牌失效）', ['responses'])
  }

  if (requiresAuth && !responses['403']) {
    push('需要认证的 operation 必须声明 403（无权限/超出数据范围）', ['responses'])
  }
  if (isUnauthenticated && responses['403']) {
    push('未认证 operation（security: []）不得声明 403', ['responses', '403'])
  }

  if (hasRequestBody && !responses['400']) {
    push('带 requestBody 的 operation 必须声明 400（字段级入参校验失败）', ['responses'])
  }

  if (hasPathParam && !responses['404']) {
    push('路径含资源参数 {…} 的 operation 必须声明 404（资源不存在或不可见）', ['responses'])
  }
  if (!hasPathParam && responses['404']) {
    push('404 仅用于含资源路径参数的单资源 operation；集合/创建接口不得声明 404', [
      'responses',
      '404',
    ])
  }

  if (!hasRequestBody && responses['409']) {
    push('409 仅用于状态变更操作（带 requestBody）；查询类 operation 不得声明 409', [
      'responses',
      '409',
    ])
  }
  if (isUnauthenticated && responses['409']) {
    push('未认证 operation（security: []）不得声明 409', ['responses', '409'])
  }

  return errors.length > 0 ? errors : undefined
}
