import axios from 'axios';
import { getMockState, saveMockState } from './mock/store';

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const mockAdapter = async (config) => {
  await wait(200 + Math.round(Math.random() * 300));
  const state = getMockState();
  const path = config.url?.replace(/^\//, '') || '';
  const method = (config.method || 'get').toLowerCase();

  if (path === 'warehouses' && method === 'get') return { data: state.warehouses, status: 200, statusText: 'OK', headers: {}, config };
  if (path === 'inventory' && method === 'get') return { data: state.inventory.map((item) => ({ ...item, available: item.onHand - item.reserved })), status: 200, statusText: 'OK', headers: {}, config };
  if (path === 'orders' && method === 'get') return { data: state.orders, status: 200, statusText: 'OK', headers: {}, config };
  if (path === 'notifications' && method === 'get') return { data: state.notifications, status: 200, statusText: 'OK', headers: {}, config };
  if (path === 'auth/login' && method === 'post') {
    const body = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
    const users = { 'admin@invensense.com': ['Admin@123', 'Aarav Mehta', 'ADMIN'], 'manager.pune@invensense.com': ['Manager@123', 'Priya Nair', 'WAREHOUSE_MANAGER'], 'procure@invensense.com': ['Procure@123', 'Kabir Shah', 'PROCUREMENT'], 'sales@invensense.com': ['Sales@123', 'Riya Sharma', 'SALES'] };
    const user = users[body.email];
    if (!user || user[0] !== body.password) throw { response: { data: { message: 'Invalid email or password' }, status: 401 }, config };
    return { data: { token: `mock-token-${Date.now()}`, refreshToken: 'mock-refresh-token', user: { id: body.email, name: user[1], email: body.email, role: user[2] } }, status: 200, statusText: 'OK', headers: {}, config };
  }
  saveMockState(state);
  return { data: {}, status: 200, statusText: 'OK', headers: {}, config };
};

export const api = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api' });
api.defaults.adapter = import.meta.env.VITE_USE_MOCK !== 'false' ? mockAdapter : undefined;
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('invensense-token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});
