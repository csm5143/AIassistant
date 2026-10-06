export const MAX_DOCUMENT_SIZE_MB = 100
export const MAX_DOCUMENT_SIZE_BYTES = MAX_DOCUMENT_SIZE_MB * 1024 * 1024

export function documentUploadError(file: { size: number }): string | null {
  if (file.size === 0) return '文档不能为空'
  if (file.size > MAX_DOCUMENT_SIZE_BYTES) return `单个文档不能超过 ${MAX_DOCUMENT_SIZE_MB} MB`
  return null
}
