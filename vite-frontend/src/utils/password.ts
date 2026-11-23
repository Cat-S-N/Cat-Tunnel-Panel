export interface PasswordStrengthResult {
  score: number;
  level: 'weak' | 'medium' | 'strong';
  label: '弱' | '中' | '强';
  color: 'danger' | 'warning' | 'success';
  percent: number;
}

/**
 * 综合评估密码强度（长度、大小写字母、数字、特殊字符）
 */
export function evaluatePasswordStrength(password: string): PasswordStrengthResult {
  if (!password) {
    return {
      score: 0,
      level: 'weak',
      label: '弱',
      color: 'danger',
      percent: 0,
    };
  }

  let score = 0;
  if (password.length >= 6) score += 1;
  if (password.length >= 10) score += 1;
  if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score += 1;
  if (/\d/.test(password)) score += 1;
  if (/[^A-Za-z0-9]/.test(password)) score += 1;

  if (score <= 2) {
    return {
      score,
      level: 'weak',
      label: '弱',
      color: 'danger',
      percent: 33,
    };
  }

  if (score <= 3) {
    return {
      score,
      level: 'medium',
      label: '中',
      color: 'warning',
      percent: 66,
    };
  }

  return {
    score,
    level: 'strong',
    label: '强',
    color: 'success',
    percent: 100,
  };
}
