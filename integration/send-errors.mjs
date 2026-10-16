/** Local error copy only. Never display upstream exception text or credentials. */
export function sendFailureMessage(code,selection) {
 if(selection?.probeRequired===true && ['UNSUPPORTED','INVALID_REQUEST','INVALID_ARGUMENT','PROTOCOL_ERROR'].includes(code))return '自定义模型尚未完成验证，请到设置测试图片与工具能力；文字已保留。';
 const messages={
  BACKEND_UNAVAILABLE:'模型或运行环境暂不可用，请检查设置后重试；文字已保留。',
  IO_ERROR:'请求未完成，请检查网络或服务状态后重试；文字已保留。',
  PERMISSION_MISSING:'当前操作缺少所需权限，请检查设置；文字已保留。',
  STALE_AUTHORIZATION:'本次授权已更新，请重新发送；文字已保留。',
  PERSISTENCE_ERROR:'本地保存失败，本次执行已停止；文字已保留。',
  TIMEOUT:'请求未收到确认，请检查连接后重试；文字已保留。',
  LIMIT_EXCEEDED:'本次请求超出数据或调用预算，请在当前会话继续；文字已保留。'
 };
 return messages[code]??'暂时无法发送，请检查模型设置或稍后重试；文字已保留。';
}
