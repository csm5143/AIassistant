import { documentUploadError } from './upload'
export const FOLDER_EXTENSIONS = new Set(['pdf', 'txt', 'md', 'doc', 'docx', 'xls', 'xlsx'])
const excluded = new Set(['.git', '.svn', '.idea', '.vscode', 'node_modules', '$recycle.bin', 'system volume information'])
export function folderFilePath(file: File): string {
  return (file.webkitRelativePath || file.name).replace(/\\/g, '/')
}
export function folderFileError(file: File): string | null {
  const path = folderFilePath(file)
  const parts = path.split('/')
  if (path.includes(':') || parts.some(p => !p.trim() || p === '.' || p === '..')) return '文件路径无效'
  if (parts.slice(0, -1).some(p => excluded.has(p.toLowerCase()))) return '已跳过非资料目录'
  if (path.length > 1024) return '文件路径过长'
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  if (!FOLDER_EXTENSIONS.has(ext)) return '不支持的文件格式'
  return documentUploadError(file)
}
