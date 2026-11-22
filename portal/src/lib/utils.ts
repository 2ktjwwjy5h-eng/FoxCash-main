import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** junta classes condicionais e resolve conflitos de utilitários Tailwind. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
