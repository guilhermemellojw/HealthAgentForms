import React from "react";

export interface SelectProps extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, "onChange"> {
  className?: string;
  placeholder?: string;
  options: Array<{ value: string; label: string; disabled?: boolean }>;
  onChange?: (value: string) => void;
}

export const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, placeholder, options, onChange, value, defaultValue, disabled, ...props }, ref) => {
    const handleChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
      onChange?.(e.target.value);
    };
    return (
      <select
        ref={ref}
        value={value}
        defaultValue={defaultValue}
        className={`w-full rounded-md border border-[var(--border)] bg-[var(--surface)] py-2 ps-3 pr-8 text-sm shadow-none focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--primary)] focus-visible:ring-offset-2 ${className || ""}`}
        onChange={handleChange}
        disabled={disabled}
        {...props}
      >
        {placeholder && <option value="">{placeholder}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value} disabled={option.disabled}>
            {option.label}
          </option>
        ))}
      </select>
    );
  },
);
Select.displayName = "Select";
