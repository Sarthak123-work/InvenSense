import React from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export function ProtectedRoute({ children }) {
  const { isAuthenticated, loading } = useAuth();
  if (loading) return React.createElement('div', { className: 'auth-loading' }, React.createElement('span', { className: 'spinner-border spinner-border-sm' }));
  if (!isAuthenticated) return React.createElement(Navigate, { to: '/login', replace: true });
  return children;
}

export function PublicOnlyRoute({ children }) {
  const { isAuthenticated, loading } = useAuth();
  if (loading) return React.createElement('div', { className: 'auth-loading' }, React.createElement('span', { className: 'spinner-border spinner-border-sm' }));
  if (isAuthenticated) return React.createElement(Navigate, { to: '/', replace: true });
  return children;
}
