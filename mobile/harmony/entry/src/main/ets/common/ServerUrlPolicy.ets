const HTTPS_PREFIX = 'https://';

function validatedOrigin(value: string): string {
  const match = /^https:\/\/([^\/?#]+)(?:[\/?#]|$)/i.exec(value.trim());
  if (!match || match.length < 2) {
    return '';
  }
  const authority = match[1];
  if (authority.includes('@') || /\s/.test(authority)) {
    return '';
  }

  let portText = '';
  if (authority.startsWith('[')) {
    const ipv6 = /^(\[[0-9a-f:.]+\])(?::(\d+))?$/i.exec(authority);
    if (!ipv6) {
      return '';
    }
    portText = ipv6[2] || '';
  } else {
    const host = /^([^:]+)(?::(\d+))?$/.exec(authority);
    if (!host || host[1] === '') {
      return '';
    }
    portText = host[2] || '';
  }
  if (portText !== '') {
    const port = Number(portText);
    if (port < 1 || port > 65535) {
      return '';
    }
  }
  return HTTPS_PREFIX + authority.toLowerCase();
}

/** 从任意 HTTPS 页面 URL 提取并校验 origin，供 ArkWeb 同源判断。 */
export function extractHttpsOrigin(value: string): string {
  return validatedOrigin(value);
}

/**
 * 接受 origin 或 origin/api，并规范为 origin 根路径 /。
 * 查询、片段、用户信息和其他路径全部失败关闭。
 */
export function canonicalAppUrl(raw: string): string {
  let value = raw.trim();
  if (value === '') {
    return '';
  }
  if (!value.includes('://')) {
    value = HTTPS_PREFIX + value;
  }
  const match = /^https:\/\/([^\/?#]+)(\/[^?#]*)?$/i.exec(value);
  if (!match) {
    return '';
  }
  const origin = validatedOrigin(value);
  if (origin === '') {
    return '';
  }
  const path = match[2] || '';
  if (path !== '' && path !== '/' && path !== '/api' && path !== '/api/') {
    return '';
  }
  return origin + '/';
}

/** 固定的公开连通性探测端点，避免 /api/api 重复拼接。 */
export function appInfoUrl(appUrl: string): string {
  const origin = validatedOrigin(appUrl);
  return origin === '' ? '' : origin + '/api/app/info';
}
