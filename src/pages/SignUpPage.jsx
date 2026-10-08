import React from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useForm } from 'react-hook-form';
import { useAuth } from '../context/AuthContext';

const warehouses = [
  { id: 'wh-mum', name: 'Mumbai' },
  { id: 'wh-pun', name: 'Pune' },
  { id: 'wh-del', name: 'Delhi' },
];

export function SignUpPage() {
  const navigate = useNavigate();
  const { register: registerUser } = useAuth();
  const [showPassword, setShowPassword] = React.useState(false);
  const [showConfirm, setShowConfirm] = React.useState(false);
  const [serverError, setServerError] = React.useState('');
  const [submitting, setSubmitting] = React.useState(false);
  const [toast, setToast] = React.useState('');

  const { register, handleSubmit, watch, formState: { errors } } = useForm({
    defaultValues: { name: '', email: '', password: '', confirmPassword: '', role: '', warehouseId: '' },
  });

  const role = watch('role');
  const passwordValue = watch('password');

  const onSubmit = async (data) => {
    setServerError('');
    setSubmitting(true);
    try {
      await registerUser(data.name, data.email, data.password, data.role, data.warehouseId || null);
      setToast('Account created successfully! Redirecting to login...');
      setTimeout(() => {
        navigate('/login', { state: { prefillEmail: data.email } });
      }, 1200);
    } catch (err) {
      const msg = err?.response?.data?.message || 'Something went wrong. Try again.';
      setServerError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  return React.createElement('div', { className: 'auth-page' },
    React.createElement('div', { className: 'auth-card-wrapper' },
      React.createElement('div', { className: 'auth-brand' },
        React.createElement('span', { className: 'auth-brand-mark' }, React.createElement('i', { className: 'bi bi-boxes' })),
        React.createElement('span', { className: 'auth-brand-name' }, 'inven', React.createElement('span', null, 'sense')),
      ),
      React.createElement('p', { className: 'auth-tagline' }, 'Smart multi-warehouse inventory'),
      React.createElement('div', { className: 'auth-card' },
        React.createElement('h2', { className: 'auth-title' }, 'Create your account'),
        React.createElement('p', { className: 'auth-subtitle' }, 'Sign up to start managing your inventory'),
        serverError && React.createElement('div', { className: 'auth-error-banner' }, React.createElement('i', { className: 'bi bi-exclamation-circle' }), ' ', serverError),
        toast && React.createElement('div', { className: 'auth-toast' }, React.createElement('i', { className: 'bi bi-check-circle' }), ' ', toast),
        React.createElement('form', { onSubmit: handleSubmit(onSubmit), noValidate: true },
          React.createElement('div', { className: 'auth-field' },
            React.createElement('label', null, 'Full name'),
            React.createElement('input', {
              type: 'text', className: 'form-control' + (errors.name ? ' is-invalid' : ''),
              placeholder: 'Enter your full name',
              ...register('name', { required: 'Full name is required' }),
            }),
            errors.name && React.createElement('span', { className: 'auth-error' }, errors.name.message),
          ),
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
                placeholder: 'Min 8 chars, 1 uppercase, 1 number',
                ...register('password', {
                  required: 'Password is required',
                  pattern: {
                    value: /^(?=.*[A-Z])(?=.*\d).{8,}$/,
                    message: 'Password must be at least 8 characters with 1 uppercase and 1 number',
                  },
                }),
              }),
              React.createElement('button', {
                type: 'button', className: 'auth-password-toggle',
                onClick: () => setShowPassword(!showPassword),
              }, React.createElement('i', { className: 'bi ' + (showPassword ? 'bi-eye-slash' : 'bi-eye') })),
            ),
            errors.password && React.createElement('span', { className: 'auth-error' }, errors.password.message),
          ),
          React.createElement('div', { className: 'auth-field' },
            React.createElement('label', null, 'Confirm password'),
            React.createElement('div', { className: 'auth-password-wrap' },
              React.createElement('input', {
                type: showConfirm ? 'text' : 'password', className: 'form-control' + (errors.confirmPassword ? ' is-invalid' : ''),
                placeholder: 'Re-enter your password',
                ...register('confirmPassword', {
                  required: 'Please confirm your password',
                  validate: v => v === passwordValue || 'Passwords do not match',
                }),
              }),
              React.createElement('button', {
                type: 'button', className: 'auth-password-toggle',
                onClick: () => setShowConfirm(!showConfirm),
              }, React.createElement('i', { className: 'bi ' + (showConfirm ? 'bi-eye-slash' : 'bi-eye') })),
            ),
            errors.confirmPassword && React.createElement('span', { className: 'auth-error' }, errors.confirmPassword.message),
          ),
          React.createElement('div', { className: 'auth-field' },
            React.createElement('label', null, 'Role'),
            React.createElement('select', {
              className: 'form-select' + (errors.role ? ' is-invalid' : ''),
              ...register('role', { required: 'Please select a role' }),
            },
              React.createElement('option', { value: '' }, 'Select your role'),
              React.createElement('option', { value: 'ADMIN' }, 'ADMIN'),
              React.createElement('option', { value: 'WAREHOUSE_MANAGER' }, 'WAREHOUSE_MANAGER'),
              React.createElement('option', { value: 'PROCUREMENT' }, 'PROCUREMENT'),
              React.createElement('option', { value: 'SALES' }, 'SALES'),
            ),
            errors.role && React.createElement('span', { className: 'auth-error' }, errors.role.message),
          ),
          role === 'WAREHOUSE_MANAGER' && React.createElement('div', { className: 'auth-field' },
            React.createElement('label', null, 'Warehouse'),
            React.createElement('select', {
              className: 'form-select',
              ...register('warehouseId'),
            },
              React.createElement('option', { value: '' }, 'Select warehouse'),
              warehouses.map(w => React.createElement('option', { key: w.id, value: w.id }, w.name)),
            ),
          ),
          React.createElement('button', {
            type: 'submit', className: 'btn btn-primary auth-submit-btn',
            disabled: submitting,
          },
            submitting ? React.createElement(React.Fragment, null, React.createElement('span', { className: 'spinner-border spinner-border-sm me-2' }), 'Creating account...') : 'Sign up',
          ),
        ),
        React.createElement('p', { className: 'auth-switch' },
          'Already have an account? ',
          React.createElement(Link, { to: '/login' }, 'Login'),
        ),
      ),
    ),
  );
}
