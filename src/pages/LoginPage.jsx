import React from 'react';
import { useNavigate, useLocation, Link } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { useAuth } from '../context/AuthContext';

const demoAccounts = [
  { label: 'Admin', email: 'admin@invensense.com', password: 'Admin@123', role: 'ADMIN' },
  { label: 'Pune Manager', email: 'manager.pune@invensense.com', password: 'Manager@123', role: 'WAREHOUSE_MANAGER' },
  { label: 'Procurement', email: 'procure@invensense.com', password: 'Procure@123', role: 'PROCUREMENT' },
  { label: 'Sales', email: 'sales@invensense.com', password: 'Sales@123', role: 'SALES' },
];

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { login } = useAuth();
  const [showPassword, setShowPassword] = React.useState(false);
  const [serverError, setServerError] = React.useState('');
  const [submitting, setSubmitting] = React.useState(false);

  const prefillEmail = location.state?.prefillEmail || '';

  const { register, handleSubmit, setValue, formState: { errors } } = useForm({
    defaultValues: { email: prefillEmail, password: '' },
  });

  const onSubmit = async (data) => {
    setServerError('');
    setSubmitting(true);
    try {
      await login(data.email, data.password);
      navigate('/');
    } catch (err) {
      const msg = err?.response?.data?.message || 'Something went wrong. Try again.';
      setServerError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  const quickFill = (account) => {
    setValue('email', account.email);
    setValue('password', account.password);
    setServerError('');
  };

  return React.createElement('div', { className: 'auth-page' },
    React.createElement('div', { className: 'auth-card-wrapper' },
      React.createElement('div', { className: 'auth-brand' },
        React.createElement('span', { className: 'auth-brand-mark' }, React.createElement('i', { className: 'bi bi-boxes' })),
        React.createElement('span', { className: 'auth-brand-name' }, 'inven', React.createElement('span', null, 'sense')),
      ),
      React.createElement('p', { className: 'auth-tagline' }, 'Smart multi-warehouse inventory'),
      React.createElement('div', { className: 'auth-card' },
        React.createElement('h2', { className: 'auth-title' }, 'Welcome back'),
        React.createElement('p', { className: 'auth-subtitle' }, 'Log in to your InvenSense dashboard'),
        serverError && React.createElement('div', { className: 'auth-error-banner' }, React.createElement('i', { className: 'bi bi-exclamation-circle' }), ' ', serverError),
        React.createElement('form', { onSubmit: handleSubmit(onSubmit), noValidate: true },
          React.createElement('div', { className: 'auth-field' },
            React.createElement('label', null, 'Email'),
            React.createElement('input', {
              type: 'email', className: 'form-control' + (errors.email ? ' is-invalid' : ''),
              placeholder: 'you@example.com',
              ...register('email', {
                required: 'Email is required',
                pattern: { value: /^[^\s@]+@[^\s@]+\.[^\s@]+$/, message: 'Enter a valid email address' },
              }),
            }),
            errors.email && React.createElement('span', { className: 'auth-error' }, errors.email.message),
          ),
          React.createElement('div', { className: 'auth-field' },
            React.createElement('label', null, 'Password'),
            React.createElement('div', { className: 'auth-password-wrap' },
              React.createElement('input', {
                type: showPassword ? 'text' : 'password', className: 'form-control' + (errors.password ? ' is-invalid' : ''),
                placeholder: 'Enter your password',
                ...register('password', { required: 'Password is required' }),
              }),
              React.createElement('button', {
                type: 'button', className: 'auth-password-toggle',
                onClick: () => setShowPassword(!showPassword),
              }, React.createElement('i', { className: 'bi ' + (showPassword ? 'bi-eye-slash' : 'bi-eye') })),
            ),
            errors.password && React.createElement('span', { className: 'auth-error' }, errors.password.message),
          ),
          React.createElement('button', {
            type: 'submit', className: 'btn btn-primary auth-submit-btn',
            disabled: submitting,
          },
            submitting ? React.createElement(React.Fragment, null, React.createElement('span', { className: 'spinner-border spinner-border-sm me-2' }), 'Logging in...') : 'Log in',
          ),
        ),
        React.createElement('div', { className: 'auth-demo-section' },
          React.createElement('div', { className: 'auth-demo-label' }, 'Quick demo accounts'),
          React.createElement('div', { className: 'auth-demo-buttons' },
            demoAccounts.map(acc => React.createElement('button', {
              key: acc.email, type: 'button',
              className: 'auth-demo-btn',
              onClick: () => quickFill(acc),
              title: `${acc.email} · ${acc.role}`,
            }, acc.label)),
          ),
        ),
        React.createElement('p', { className: 'auth-switch' },
          'New here? ',
          React.createElement(Link, { to: '/signup' }, 'Sign up'),
        ),
      ),
    ),
  );
}
