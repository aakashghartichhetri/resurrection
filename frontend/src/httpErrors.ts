/** Keep API failures understandable and include the request ID an operator can find in logs. */
export function explainApiError(status: number, reason?: string, requestId?: string | null): string {
  if (status === 401) return 'That username or password did not work.'
  const explanation = reason?.trim() || `The request failed (HTTP ${status}).`
  return requestId && /^[A-Za-z0-9_-]{8,64}$/.test(requestId)
    ? `${explanation} Reference: ${requestId}`
    : explanation
}
