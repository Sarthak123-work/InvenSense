const USERS_KEY = 'invensense-auth-users';
const SESSION_KEY = 'invensense-auth-session';

const demoUsers = [
  { id: 'usr-admin', name: 'Aarav Mehta', email: 'admin@invensense.com', password: 'Admin@123', role: 'ADMIN', warehouseId: null },
  { id: 'usr-mgr-pune', name: 'Priya Sharma', email: 'manager.pune@invensense.com', password: 'Manager@123', role: 'WAREHOUSE_MANAGER', warehouseId: 'wh-pun' },
  { id: 'usr-procure', name: 'Rohan Kapoor', email: 'procure@invensense.com', password: 'Procure@123', role: 'PROCUREMENT', warehouseId: null },
  { id: 'usr-sales', name: 'Sneha Reddy', email: 'sales@invensense.com', password: 'Sales@123', role: 'SALES', warehouseId: null },
];

export function getUsers() {
  const saved = localStorage.getItem(USERS_KEY);
  if (saved) {
    try { return JSON.parse(saved); } catch { /* fall through */ }
  }
  localStorage.setItem(USERS_KEY, JSON.stringify(demoUsers));
  return [...demoUsers];
}

export function saveUsers(users) {
  localStorage.setItem(USERS_KEY, JSON.stringify(users));
}

export function findUserByEmail(email) {
  return getUsers().find(u => u.email.toLowerCase() === email.toLowerCase());
}

export function addUser(user) {
  const users = getUsers();
  users.push(user);
  saveUsers(users);
}

export function generateToken(user) {
  return btoa(`${user.id}:${user.email}:${Date.now()}`);
}

export function sanitizeUser(user) {
  const { password, ...rest } = user;
  return rest;
}

export function saveSession(token, user) {
  localStorage.setItem(SESSION_KEY, JSON.stringify({ token, user }));
}

export function getSession() {
  const saved = localStorage.getItem(SESSION_KEY);
  if (!saved) return null;
  try { return JSON.parse(saved); } catch { return null; }
}

export function clearSession() {
  localStorage.removeItem(SESSION_KEY);
}
