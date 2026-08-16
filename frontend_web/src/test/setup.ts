import '@testing-library/jest-dom/vitest'

// Node 22+ 的全局 localStorage 需要额外开关，可能覆盖 jsdom 的存储实现；
// 测试统一绑定到带固定 origin 的 jsdom localStorage。
if (typeof window !== 'undefined') {
  const memory = new Map<string, string>()
  const fallbackStorage = {
    get length() { return memory.size },
    clear: () => memory.clear(),
    getItem: (key: string) => memory.get(key) ?? null,
    key: (index: number) => [...memory.keys()][index] ?? null,
    removeItem: (key: string) => { memory.delete(key) },
    setItem: (key: string, value: string) => { memory.set(key, String(value)) },
  } as Storage
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: window.localStorage ?? fallbackStorage,
  })
}
