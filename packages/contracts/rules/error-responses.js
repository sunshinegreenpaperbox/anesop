/**
 * Spectral 自定义函数：校验每个 operation 是否声明 401/403/404/409。
 *
 * 规则见 packages/contracts/.spectral.yaml 的 error-responses。
 * 只做结构检查，不校验响应内容；错误模型与示例由 components.responses 统一约束。
 */
module.exports = function errorResponses(targetVal) {
  const required = ['401', '403', '404', '409']
  const responses = (targetVal && targetVal.responses) || {}
  const missing = required.filter(
    (status) => !Object.prototype.hasOwnProperty.call(responses, status),
  )

  if (missing.length === 0) {
    return
  }

  return [
    {
      message: `每个 operation 必须声明 401/403/404/409 错误响应，缺少：${missing.join('、')}`,
    },
  ]
}
