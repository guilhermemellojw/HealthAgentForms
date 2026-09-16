import React from "react";

export interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  className?: string;
  shadow?: "sm" | "md" | "lg";
  children: React.ReactNode;
}

export const Card = React.forwardRef<HTMLDivElement, CardProps>(({ className, shadow = "md", children, ...props }, ref) => {
  const shadowClass = {
    sm: "shadow-sm",
    md: "shadow-md",
    lg: "shadow-lg",
  }[shadow];

  return (
    <div ref={ref} className={`rounded-md p-6 border border-[var(--border)] bg-[var(--surface)] ${shadowClass} ${className || ""}`} {...props}>
      {children}
    </div>
  );
});
Card.displayName = "Card";
