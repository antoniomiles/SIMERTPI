export function formatDashboardAmount(amount: string, currency: string) {
  if (!/^-?\d+(\.\d{1,2})?$/.test(amount)) return `${currency} ${amount}`;
  const [whole, fraction = ''] = amount.split('.');
  const grouped = new Intl.NumberFormat('es-EC', { maximumFractionDigits: 0 }).format(
    BigInt(whole),
  );
  return `${currency} ${grouped},${fraction.padEnd(2, '0')}`;
}
