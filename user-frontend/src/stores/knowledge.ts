import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { KbCollection, KbDocument } from '@/api'
import { getCollections, createCollection, deleteCollection, getDocuments } from '@/api'

export const useKnowledgeStore = defineStore('knowledge', () => {
  const collections = ref<KbCollection[]>([])
  const documents = ref<KbDocument[]>([])
  const loading = ref(false)

  async function fetchCollections() {
    loading.value = true
    try {
      const { data } = await getCollections()
      collections.value = data.data || []
    } finally {
      loading.value = false
    }
  }

  async function addCollection(name: string, description?: string) {
    const { data } = await createCollection({ name, description })
    collections.value.unshift(data.data)
    return data.data
  }

  async function removeCollection(id: string) {
    await deleteCollection(id)
    collections.value = collections.value.filter(c => c.id !== id)
  }

  let documentRequest = 0
  let activeCollection: string | null = null
  async function fetchDocuments(collectionId: string) {
    const request = ++documentRequest
    if (activeCollection !== collectionId) documents.value = []
    activeCollection = collectionId
    const { data } = await getDocuments(collectionId)
    if (request === documentRequest) documents.value = data.data || []
  }
  function clearDocuments() {
    ++documentRequest
    activeCollection = null
    documents.value = []
  }

  return { collections, documents, loading, fetchCollections, addCollection, removeCollection, fetchDocuments, clearDocuments }
})
