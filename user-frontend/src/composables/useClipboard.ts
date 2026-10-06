import { ref } from 'vue'

export function useClipboard() {
  const copied = ref(false)

  async function copy(text: string): Promise<boolean> {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text)
        copied.value = true
        setTimeout(() => { copied.value = false }, 2000)
        return true
      } else {
        // Fallback for non-secure contexts
        const textArea = document.createElement('textarea')
        textArea.value = text
        textArea.style.position = 'fixed'
        textArea.style.left = '-999999px'
        textArea.style.top = '-999999px'
        document.body.appendChild(textArea)
        textArea.focus()
        textArea.select()
        const success = document.execCommand('copy')
        document.body.removeChild(textArea)
        if (success) {
          copied.value = true
          setTimeout(() => { copied.value = false }, 2000)
        }
        return success
      }
    } catch (err) {
      console.error('Copy failed:', err)
      return false
    }
  }

  return { copy, copied }
}
