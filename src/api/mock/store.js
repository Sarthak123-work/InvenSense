const STORAGE_KEY = 'invensense-demo-state';

const seed = {
  warehouses: [{ id: 'wh-mum', name: 'Mumbai', city: 'Mumbai', capacity: 10000 }, { id: 'wh-pun', name: 'Pune', city: 'Pune', capacity: 8000 }, { id: 'wh-del', name: 'Delhi', city: 'Delhi', capacity: 12000 }],
  inventory: [{ warehouseId: 'wh-pun', sku: 'SKU-1001', onHand: 12, reserved: 0, reorderPoint: 40, version: 37 }, { warehouseId: 'wh-mum', sku: 'SKU-1012', onHand: 0, reserved: 0, reorderPoint: 20, version: 22 }],
  orders: [],
  notifications: [{ id: 'n-1', type: 'LOW_STOCK', message: 'SKU-1001 is low in Pune (12 left)', read: false }]
};

export function getMockState() {
  const saved = localStorage.getItem(STORAGE_KEY);
  return saved ? JSON.parse(saved) : structuredClone(seed);
}

export function saveMockState(state) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

export function resetMockState() {
  localStorage.removeItem(STORAGE_KEY);
}
