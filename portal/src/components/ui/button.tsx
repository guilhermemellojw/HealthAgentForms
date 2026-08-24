import React from "react";

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: "primary" | "secondary" | "danger";
  size?: "sm" | "md" | "lg";
  children?: React.ReactNode;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = "primary", size = "md", children, type, disabled, ...attrs }, ref) => {
    const sizeClass = {
      sm: "h-8 py-2 px-3",
      md: "h-10 py-2 px-4",
      lg: "h-12 py-3 px-6",
    }[size];

    const variantClass = {
      primary: "bg-primary text-white hover:bg-primary-dark",
      secondary: "bg-secondary text-white hover:bg-secondary-dark",
      danger: "bg-danger text-white hover:bg-danger-dark",
    }[variant];

    const disabledClass = disabled ? "opacity-50 cursor-not-allowed" : "";

    return (
      <button
        ref={ref}
        type={type ?? "button"}
        disabled={disabled}
        className={`inline-flex items-center justify-center rounded-md ${sizeClass} ${variantClass} font-medium ${disabledClass} ${className || ""}`}
        {...attrs}
      >
        {children}
      </button>
    );
  },
);
Button.displayName = "Button";
